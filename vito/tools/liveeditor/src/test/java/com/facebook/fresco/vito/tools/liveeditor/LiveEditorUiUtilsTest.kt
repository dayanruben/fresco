/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.tools.liveeditor

import android.graphics.Bitmap
import android.net.Uri
import com.facebook.fresco.vito.source.ImageSource
import com.facebook.fresco.vito.source.ImageSourceProvider
import com.facebook.fresco.vito.tools.liveeditor.LiveEditorUiUtils.Companion.sourceInfoRows
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveEditorUiUtilsTest {

  @Test
  fun testSourceInfoRows_whenSingleUriSource_thenReturnsTypeAndUrl() {
    val url = "https://scontent.example/x.jpg?a=1&_nc_ohc=abc,def"
    val source = ImageSourceProvider.forUri(Uri.parse(url))
    assertThat(sourceInfoRows(source))
        .containsExactly("Source type" to "SingleImageSource", "Image URL" to url)
  }

  @Test
  fun testSourceInfoRows_whenIncreasingQuality_thenReturnsLowAndHighRows() {
    val low = "https://scontent.example/low.jpg?a=1"
    val high = "https://scontent.example/high.jpg?b=2,3"
    val source = ImageSourceProvider.increasingQuality(Uri.parse(low), Uri.parse(high))
    assertThat(sourceInfoRows(source))
        .containsExactly(
            "Source type" to "IncreasingQualityImageSource",
            "Image URL (low-res)" to low,
            "Image URL (high-res)" to high,
        )
  }

  @Test
  fun testSourceInfoRows_whenFirstAvailable_thenReturnsIndexedRows() {
    val a = "https://scontent.example/a.jpg"
    val b = "https://scontent.example/b.jpg?x=1,2"
    val source =
        ImageSourceProvider.firstAvailable(
            ImageSourceProvider.forUri(Uri.parse(a)),
            ImageSourceProvider.forUri(Uri.parse(b)),
        )
    assertThat(sourceInfoRows(source))
        .containsExactly(
            "Source type" to "FirstAvailableImageSource",
            "Image URL (1/2)" to a,
            "Image URL (2/2)" to b,
        )
  }

  @Test
  fun testSourceInfoRows_whenEmptySource_thenReturnsSingleTypeRow() {
    val rows = sourceInfoRows(ImageSourceProvider.emptySource())
    assertThat(rows).hasSize(1)
    assertThat(rows[0].first).isEqualTo("Source type")
  }

  @Test
  fun testSourceInfoRows_whenNull_thenReturnsEmpty() {
    assertThat(sourceInfoRows(null)).isEmpty()
  }

  @Test
  fun testSourceInfoRows_whenNestedComposite_thenSuffixesAccumulateOuterFirst() {
    val a = "https://scontent.example/a.jpg"
    val low = "https://scontent.example/low.jpg?a=1"
    val high = "https://scontent.example/high.jpg?b=2,3"
    val source =
        ImageSourceProvider.firstAvailable(
            ImageSourceProvider.forUri(Uri.parse(a)),
            ImageSourceProvider.increasingQuality(Uri.parse(low), Uri.parse(high)),
        )
    assertThat(sourceInfoRows(source))
        .containsExactly(
            "Source type" to "FirstAvailableImageSource",
            "Image URL (1/2)" to a,
            "Image URL (2/2) (low-res)" to low,
            "Image URL (2/2) (high-res)" to high,
        )
  }

  @Test
  fun testSourceInfoRows_whenBitmapLeaf_thenReturnsBitmapDimensions() {
    val source = ImageSourceProvider.bitmap(Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888))
    assertThat(sourceInfoRows(source))
        .containsExactly("Source type" to "BitmapImageSource", "Image (bitmap)" to "4x2")
  }

  @Test
  fun testSourceInfoRows_whenUnknownSource_thenFallsBackToToString() {
    val source =
        object : ImageSource {
          override fun getClassNameString(): String = "CustomTestImageSource"

          override fun toString(): String = "CustomTestImageSource(uri=custom://x)"
        }
    assertThat(sourceInfoRows(source))
        .containsExactly(
            "Source type" to "CustomTestImageSource",
            "Image (source)" to "CustomTestImageSource(uri=custom://x)",
        )
  }
}
