/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers;

import androidx.annotation.VisibleForTesting;
import bolts.Continuation;
import bolts.Task;
import com.facebook.cache.common.CacheKey;
import com.facebook.common.internal.ImmutableMap;
import com.facebook.common.internal.Supplier;
import com.facebook.fresco.middleware.HasExtraData;
import com.facebook.imagepipeline.cache.BufferedDiskCache;
import com.facebook.imagepipeline.cache.CacheKeyFactory;
import com.facebook.imagepipeline.core.DiskCachesStore;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.infer.annotation.Nullsafe;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * Disk cache read producer.
 *
 * <p>This producer looks in the disk cache for the requested image. If the image is found, then it
 * is passed to the consumer. If the image is not found, then the request is passed to the next
 * producer in the sequence. Any results that the producer returns are passed to the consumer.
 *
 * <p>This implementation delegates disk cache requests to BufferedDiskCache.
 *
 * <p>This producer is currently used only if the media variations experiment is turned on, to
 * enable another producer to sit between cache read and write.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class DiskCacheReadProducer implements Producer<EncodedImage> {
  // PRODUCER_NAME doesn't exactly match class name as it matches name in historic data instead
  public static final String PRODUCER_NAME = "DiskCacheProducer";

  public static final String EXTRA_CACHED_VALUE_FOUND = ProducerConstants.EXTRA_CACHED_VALUE_FOUND;

  public static final String ENCODED_IMAGE_SIZE = ProducerConstants.ENCODED_IMAGE_SIZE;

  private final Supplier<DiskCachesStore> mDiskCachesStoreSupplier;
  private final CacheKeyFactory mCacheKeyFactory;
  private final Producer<EncodedImage> mInputProducer;

  public DiskCacheReadProducer(
      Supplier<DiskCachesStore> diskCachesStoreSupplier,
      CacheKeyFactory cacheKeyFactory,
      Producer<EncodedImage> inputProducer) {
    mDiskCachesStoreSupplier = diskCachesStoreSupplier;
    mCacheKeyFactory = cacheKeyFactory;
    mInputProducer = inputProducer;
  }

  public void produceResults(
      final Consumer<EncodedImage> consumer, final ProducerContext producerContext) {
    final ImageRequest imageRequest = producerContext.getImageRequest();
    final boolean isDiskCacheEnabledForRead =
        producerContext
            .getImageRequest()
            .isCacheEnabled(ImageRequest.CachesLocationsMasks.DISK_READ);
    if (!isDiskCacheEnabledForRead) {
      maybeStartInputProducer(consumer, producerContext);
      return;
    }

    producerContext.getProducerListener().onProducerStart(producerContext, PRODUCER_NAME);

    final CacheKey cacheKey =
        mCacheKeyFactory.getEncodedCacheKey(imageRequest, producerContext.getCallerContext());
    final DiskCachesStore diskCachesStore = mDiskCachesStoreSupplier.get();
    final BufferedDiskCache preferredCache =
        DiskCacheDecision.chooseDiskCacheForRequest(
            imageRequest,
            diskCachesStore.getSmallImageBufferedDiskCache(),
            diskCachesStore.getMainBufferedDiskCache(),
            diskCachesStore.getDynamicBufferedDiskCaches());
    if (preferredCache == null) {
      producerContext
          .getProducerListener()
          .onProducerFinishWithFailure(
              producerContext,
              PRODUCER_NAME,
              new DiskCacheDecision.DiskCacheDecisionNoDiskCacheChosenException(
                  "Got no disk cache for CacheChoice: "
                      + Integer.valueOf(imageRequest.getCacheChoice().ordinal()).toString()),
              null);
      maybeStartInputProducer(consumer, producerContext);
      return;
    }
    final AtomicBoolean isCancelled = new AtomicBoolean(false);
    final Task<EncodedImage> diskLookupTask = preferredCache.get(cacheKey, isCancelled);
    final Continuation<EncodedImage, Void> continuation =
        onFinishDiskReads(consumer, producerContext);
    diskLookupTask.continueWith(continuation);
    subscribeTaskForRequestCancellation(isCancelled, producerContext);
  }

  private Continuation<EncodedImage, Void> onFinishDiskReads(
      final Consumer<EncodedImage> consumer, final ProducerContext producerContext) {
    final ProducerListener2 listener = producerContext.getProducerListener();
    return new Continuation<EncodedImage, Void>() {
      @Nullable
      @Override
      public Void then(Task<EncodedImage> task) throws Exception {
        if (isTaskCancelled(task)) {
          listener.onProducerFinishWithCancellation(producerContext, PRODUCER_NAME, null);
          consumer.onCancellation();
        } else if (task.isFaulted()) {
          listener.onProducerFinishWithFailure(
              producerContext, PRODUCER_NAME, task.getError(), null);
          mInputProducer.produceResults(consumer, producerContext);
        } else {
          EncodedImage cachedReference = task.getResult();
          if (cachedReference != null) {
            listener.onProducerFinishWithSuccess(
                producerContext,
                PRODUCER_NAME,
                getExtraMap(listener, producerContext, true, cachedReference.getSize()));
            listener.onUltimateProducerReached(producerContext, PRODUCER_NAME, true);
            producerContext.putOriginExtra("disk");
            producerContext.putExtra(HasExtraData.KEY_ENCODED_SIZE, cachedReference.getSize());
            producerContext.putExtra(HasExtraData.KEY_ENCODED_WIDTH, cachedReference.getWidth());
            producerContext.putExtra(HasExtraData.KEY_ENCODED_HEIGHT, cachedReference.getHeight());
            consumer.onProgressUpdate(1);
            consumer.onNewResult(cachedReference, Consumer.IS_LAST);
            cachedReference.close();
          } else {
            listener.onProducerFinishWithSuccess(
                producerContext, PRODUCER_NAME, getExtraMap(listener, producerContext, false, 0));
            mInputProducer.produceResults(consumer, producerContext);
          }
        }
        return null;
      }
    };
  }

  private static boolean isTaskCancelled(Task<?> task) {
    return task.isCancelled()
        || (task.isFaulted() && task.getError() instanceof CancellationException);
  }

  private void maybeStartInputProducer(
      Consumer<EncodedImage> consumer, ProducerContext producerContext) {
    if (producerContext.getLowestPermittedRequestLevel().getValue()
        >= ImageRequest.RequestLevel.DISK_CACHE.getValue()) {
      producerContext.putOriginExtra("disk", "nil-result_read");
      consumer.onNewResult(null, Consumer.IS_LAST);
      return;
    }

    mInputProducer.produceResults(consumer, producerContext);
  }

  @VisibleForTesting
  static @Nullable Map<String, String> getExtraMap(
      final ProducerListener2 listener,
      final ProducerContext producerContext,
      final boolean valueFound,
      final int sizeInBytes) {
    if (!listener.requiresExtraMap(producerContext, PRODUCER_NAME)) {
      return null;
    }
    if (valueFound) {
      return ImmutableMap.of(
          EXTRA_CACHED_VALUE_FOUND,
          String.valueOf(valueFound),
          ENCODED_IMAGE_SIZE,
          String.valueOf(sizeInBytes));
    } else {
      return ImmutableMap.of(EXTRA_CACHED_VALUE_FOUND, String.valueOf(valueFound));
    }
  }

  private void subscribeTaskForRequestCancellation(
      final AtomicBoolean isCancelled, ProducerContext producerContext) {
    producerContext.addCallbacks(
        new BaseProducerContextCallbacks() {
          @Override
          public void onCancellationRequested() {
            isCancelled.set(true);
          }
        });
  }
}
