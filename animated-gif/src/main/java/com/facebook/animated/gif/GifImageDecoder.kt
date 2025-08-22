/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.animated.gif

import com.facebook.common.logging.FLog
import com.facebook.imagepipeline.animated.base.AnimatedImageGifValidator
import com.facebook.imagepipeline.animated.factory.AnimatedImageDecoderBase
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory
import com.facebook.imagepipeline.common.ImageDecodeOptions
import com.facebook.imagepipeline.decoder.ImageDecoder
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.image.QualityInfo

class GifImageDecoder(
    platformBitmapFactory: PlatformBitmapFactory,
    isNewRenderImplementation: Boolean,
    downscaleFrameToDrawableDimensions: Boolean,
    treatAnimatedImagesAsStateful: Boolean = true,
) :
    AnimatedImageDecoderBase(
        platformBitmapFactory,
        downscaleFrameToDrawableDimensions,
        isNewRenderImplementation,
        treatAnimatedImagesAsStateful,
    ),
    ImageDecoder {

  /**
   * Decodes an animated GIF image into a CloseableImage.
   *
   * @param encodedImage encoded image (native byte array holding the encoded bytes and meta data)
   * @param length the length of the encoded data
   * @param qualityInfo quality information about the image
   * @param options decode options specifying how the image should be decoded
   * @return a CloseableImage
   */
  override fun decode(
      encodedImage: EncodedImage,
      length: Int,
      qualityInfo: QualityInfo,
      options: ImageDecodeOptions,
  ): CloseableImage? {
    val bytesRef = encodedImage.byteBufferRef
    checkNotNull(bytesRef)

    bytesRef.use {
      val validationResult = AnimatedImageGifValidator.validateImage(encodedImage)
      if (validationResult.isValid == false) {
        FLog.w(TAG, "Image validation failed: ${validationResult.message}")
        throw UnsupportedOperationException("Invalid image: ${validationResult.message}")
      }

      val input = bytesRef.get()
      val image =
          input.byteBuffer?.let { byteBuffer -> GifImage.createFromByteBuffer(byteBuffer, options) }
              ?: GifImage.createFromNativeMemory(input.nativePtr, input.size(), options)
      return getCloseableImage(
          encodedImage.source,
          options,
          checkNotNull(image),
          options.animatedBitmapConfig,
      )
    }
  }

  companion object {
    private const val TAG = "GifImageDecoder"
  }
}
