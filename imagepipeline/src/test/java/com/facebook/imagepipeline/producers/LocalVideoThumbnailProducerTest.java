/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.provider.MediaStore;
import com.facebook.common.internal.ImmutableMap;
import com.facebook.common.memory.PooledByteBufferFactory;
import com.facebook.common.references.CloseableReference;
import com.facebook.imagepipeline.common.Priority;
import com.facebook.imagepipeline.core.ImagePipelineConfig;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.image.CloseableStaticBitmap;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.testing.FakeClock;
import com.facebook.imagepipeline.testing.TestExecutorService;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.*;
import org.junit.After;
import org.junit.runner.*;
import org.mockito.*;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.invocation.*;
import org.mockito.stubbing.*;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Basic tests for {@link LocalVideoThumbnailProducer} */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LocalVideoThumbnailProducerTest {
  private static final String PRODUCER_NAME = LocalVideoThumbnailProducer.PRODUCER_NAME;
  private static final String TEST_FILENAME = "/dancing_hotdog.mp4";
  private static final String TEST_FILEPATH = "file://" + TEST_FILENAME;

  @Mock public PooledByteBufferFactory mPooledByteBufferFactory;
  @Mock public Consumer<CloseableReference<CloseableImage>> mConsumer;
  @Mock public ImageRequest mImageRequest;
  @Mock public ProducerListener2 mProducerListener;
  @Mock public Exception mException;
  @Mock public Bitmap mBitmap;
  @Mock public ImagePipelineConfig mConfig;

  private TestExecutorService mExecutor;
  private SettableProducerContext mProducerContext;
  private final String mRequestId = "mRequestId";
  private LocalVideoThumbnailProducer mLocalVideoThumbnailProducer;
  private CloseableReference<CloseableStaticBitmap> mCloseableReference;
  private android.net.Uri mLocalVideoUri;
  private MockedStatic<ThumbnailUtils> mockedThumbnailUtils;

  @Before
  public void setUp() throws Exception {
    mockedThumbnailUtils = mockStatic(ThumbnailUtils.class);
    MockitoAnnotations.initMocks(this);
    mExecutor = new TestExecutorService(new FakeClock());
    mLocalVideoThumbnailProducer =
        new LocalVideoThumbnailProducer(
            mExecutor, RuntimeEnvironment.application.getContentResolver());

    mProducerContext =
        new SettableProducerContext(
            mImageRequest,
            mRequestId,
            mProducerListener,
            mock(Object.class),
            ImageRequest.RequestLevel.FULL_FETCH,
            false,
            false,
            Priority.MEDIUM,
            mConfig);
    mLocalVideoUri = Uri.parse(TEST_FILEPATH);
  }

  @After
  public void tearDownStaticMocks() {
    mockedThumbnailUtils.close();
  }

  @Test
  public void testLocalVideoThumbnailCancelled() {
    mLocalVideoThumbnailProducer.produceResults(mConsumer, mProducerContext);
    mProducerContext.cancel();
    verify(mProducerListener).onProducerStart(mProducerContext, PRODUCER_NAME);
    verify(mProducerListener)
        .onProducerFinishWithCancellation(mProducerContext, PRODUCER_NAME, null);
    verify(mProducerListener, never())
        .onUltimateProducerReached(eq(mProducerContext), anyString(), anyBoolean());
    verify(mConsumer).onCancellation();
  }

  @Test
  public void testLocalVideoMiniThumbnailSuccess() throws Exception {
    when(mImageRequest.getPreferredWidth()).thenReturn(100);
    when(mImageRequest.getPreferredHeight()).thenReturn(100);
    when(mImageRequest.getSourceUri()).thenReturn(mLocalVideoUri);
    when(android.media.ThumbnailUtils.createVideoThumbnail(
            TEST_FILENAME, MediaStore.Images.Thumbnails.MINI_KIND))
        .thenReturn(mBitmap);
    doAnswer(
            new Answer() {
              @Nullable
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                mCloseableReference = ((CloseableReference) invocation.getArguments()[0]).clone();
                return null;
              }
            })
        .when(mConsumer)
        .onNewResult(any(CloseableReference.class), eq(Consumer.IS_LAST));
    mLocalVideoThumbnailProducer.produceResults(mConsumer, mProducerContext);
    mExecutor.runUntilIdle();
    assertEquals(1, mCloseableReference.getUnderlyingReferenceTestOnly().getRefCountTestOnly());
    assertEquals(
        mBitmap, mCloseableReference.getUnderlyingReferenceTestOnly().get().getUnderlyingBitmap());
    verify(mProducerListener).onProducerStart(mProducerContext, PRODUCER_NAME);
    verify(mProducerListener).onProducerFinishWithSuccess(mProducerContext, PRODUCER_NAME, null);
    verify(mProducerListener).onUltimateProducerReached(mProducerContext, PRODUCER_NAME, true);
  }

  @Test
  public void testLocalVideoMicroThumbnailSuccess() throws Exception {
    when(mImageRequest.getSourceUri()).thenReturn(mLocalVideoUri);
    when(mProducerListener.requiresExtraMap(mProducerContext, PRODUCER_NAME)).thenReturn(true);
    when(android.media.ThumbnailUtils.createVideoThumbnail(
            TEST_FILENAME, MediaStore.Images.Thumbnails.MICRO_KIND))
        .thenReturn(mBitmap);
    doAnswer(
            new Answer() {
              @Nullable
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                mCloseableReference = ((CloseableReference) invocation.getArguments()[0]).clone();
                return null;
              }
            })
        .when(mConsumer)
        .onNewResult(any(CloseableReference.class), eq(Consumer.IS_LAST));
    mLocalVideoThumbnailProducer.produceResults(mConsumer, mProducerContext);
    mExecutor.runUntilIdle();
    assertEquals(1, mCloseableReference.getUnderlyingReferenceTestOnly().getRefCountTestOnly());
    assertEquals(
        mBitmap, mCloseableReference.getUnderlyingReferenceTestOnly().get().getUnderlyingBitmap());
    verify(mProducerListener).onProducerStart(mProducerContext, PRODUCER_NAME);
    Map<String, String> thumbnailFoundMap =
        ImmutableMap.of(LocalVideoThumbnailProducer.CREATED_THUMBNAIL, "true");
    verify(mProducerListener)
        .onProducerFinishWithSuccess(mProducerContext, PRODUCER_NAME, thumbnailFoundMap);
    verify(mProducerListener).onUltimateProducerReached(mProducerContext, PRODUCER_NAME, true);
  }

  @Test
  public void testLocalVideoMicroThumbnailReturnsNull() throws Exception {
    when(mImageRequest.getSourceUri()).thenReturn(mLocalVideoUri);
    when(mProducerListener.requiresExtraMap(mProducerContext, PRODUCER_NAME)).thenReturn(true);
    when(android.media.ThumbnailUtils.createVideoThumbnail(
            TEST_FILENAME, MediaStore.Images.Thumbnails.MICRO_KIND))
        .thenReturn(null);
    mLocalVideoThumbnailProducer.produceResults(mConsumer, mProducerContext);
    mExecutor.runUntilIdle();
    verify(mConsumer).onNewResult(null, Consumer.IS_LAST);
    verify(mProducerListener).onProducerStart(mProducerContext, PRODUCER_NAME);
    Map<String, String> thumbnailNotFoundMap =
        ImmutableMap.of(LocalVideoThumbnailProducer.CREATED_THUMBNAIL, "false");
    verify(mProducerListener)
        .onProducerFinishWithSuccess(mProducerContext, PRODUCER_NAME, thumbnailNotFoundMap);
    verify(mProducerListener).onUltimateProducerReached(mProducerContext, PRODUCER_NAME, false);
  }

  @Test(expected = RuntimeException.class)
  public void testFetchLocalFileFailsByThrowing() throws Exception {
    when(android.media.ThumbnailUtils.createVideoThumbnail(
            TEST_FILENAME, MediaStore.Images.Thumbnails.MICRO_KIND))
        .thenThrow(mException);
    verify(mConsumer).onFailure(mException);
    verify(mProducerListener).onProducerStart(mProducerContext, PRODUCER_NAME);
    verify(mProducerListener)
        .onProducerFinishWithFailure(mProducerContext, PRODUCER_NAME, mException, null);
    verify(mProducerListener).onUltimateProducerReached(mProducerContext, PRODUCER_NAME, false);
  }
}
