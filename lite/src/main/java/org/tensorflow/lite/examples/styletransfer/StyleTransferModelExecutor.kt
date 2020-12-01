/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.styletransfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.collections.set
import org.tensorflow.lite.Interpreter


import org.tensorflow.lite.gpu.GpuDelegate

@SuppressWarnings("GoodTime")
class StyleTransferModelExecutor(
  context: Context,
  private var useGPU: Boolean = false
) {
  private var gpuDelegate: GpuDelegate? = null
  private var numberThreads = 4

  private val interpreterPredict: Interpreter
  private val interpreterTransform: Interpreter

  private var fullExecutionTime = 0L
  private var preProcessTime = 0L
  private var stylePredictTime = 0L
  private var styleTransferTime = 0L
  private var postProcessTime = 0L





  companion object {
    private const val TAG = "StyleTransferMExec"
    private const val STYLE_IMAGE_SIZE = 256
    private const val CONTENT_IMAGE_SIZE = 384
    private const val BOTTLENECK_SIZE = 100
//    private const val STYLE_PREDICT_INT_MODEL = "style_predict_hybrid_last.tflite"
//    private const val STYLE_TRANSFER_INT_MODEL = "style_transfer_hybrid_last.tflite"
//    private const val STYLE_PREDICT_FLOAT16_MODEL = "style_predict_f16_shayak.tflite"
//    private const val STYLE_TRANSFER_FLOAT16_MODEL = "style_transfer_f16_shayak.tflite"
    //not fit in xiaomi 8
    private const val STYLE_PREDICT_INT_MODEL = "style_predict_quantized_256.tflite"
    private const val STYLE_TRANSFER_INT_MODEL = "style_transfer_quantized_384.tflite"
    private const val STYLE_PREDICT_FLOAT16_MODEL = "style_predict_f16_256.tflite"
    private const val STYLE_TRANSFER_FLOAT16_MODEL = "style_transfer_f16_384.tflite"
  }
  private lateinit var inputsStyleForPredict: Array<Any>
  private lateinit var inputsContentForPredict: Array<Any>
  private lateinit var outputsForPredictStyle: HashMap<Int, Any>
  private lateinit var outputsForPredictContent: HashMap<Int, Any>

  private var contentBottleneck =
    Array(1) { Array(1) { Array(1) { FloatArray(BOTTLENECK_SIZE) } } }
  private var styleBottleneck =
    Array(1) { Array(1) { Array(1) { FloatArray(BOTTLENECK_SIZE) } } }
  private var styleBottleneckBlended =
    Array(1) { Array(1) { Array(1) { FloatArray(BOTTLENECK_SIZE) } } }

  var contentBlendingRatio = 0.6f
  init {
    if (useGPU) {
      interpreterPredict = getInterpreter(context, STYLE_PREDICT_FLOAT16_MODEL, true)
      interpreterTransform = getInterpreter(context, STYLE_TRANSFER_FLOAT16_MODEL, true)
    } else {
      interpreterPredict = getInterpreter(context, STYLE_PREDICT_INT_MODEL, false)
      interpreterTransform = getInterpreter(context, STYLE_TRANSFER_INT_MODEL, false)
    }


  }

  fun execute(
    contentImagePath: String,
    styleImageName: String,
    context: Context
  ): ModelExecutionResult {
    try {
      Log.i(TAG, "running models")

      val contentBitmap
              //= ImageUtils.decodeBitmap(File(contentImagePath))
      = MediaStore.Images.Media.getBitmap(context.contentResolver, Uri.parse(contentImagePath))
      stylePredictTime = SystemClock.uptimeMillis()
      val styleBitmap //=ImageUtils.loadBitmapFromResources(context, "thumbnails/$styleImageName")
      = MediaStore.Images.Media.getBitmap(context.contentResolver, Uri.parse(styleImageName))
      val inputStyle =
        ImageUtils.bitmapToByteBuffer(styleBitmap, STYLE_IMAGE_SIZE, STYLE_IMAGE_SIZE)

      inputsStyleForPredict = arrayOf(inputStyle)
      outputsForPredictStyle = HashMap()

      // Stylebottleneckblended is calculated once and used inside style transfer
      outputsForPredictStyle[0] = styleBottleneckBlended
      preProcessTime = SystemClock.uptimeMillis() - preProcessTime

      interpreterPredict.runForMultipleInputsOutputs(
        inputsStyleForPredict,
        outputsForPredictStyle
      )

      stylePredictTime = SystemClock.uptimeMillis() - stylePredictTime

      stylePredictTime = SystemClock.uptimeMillis()


      val inputContent =
        ImageUtils.bitmapToByteBuffer(contentBitmap,
          StyleTransferModelExecutor.STYLE_IMAGE_SIZE,
          StyleTransferModelExecutor.STYLE_IMAGE_SIZE
        )

      inputsStyleForPredict = arrayOf(inputStyle)
      inputsContentForPredict = arrayOf(inputContent)
      outputsForPredictStyle = HashMap()
      outputsForPredictContent = HashMap()
      outputsForPredictStyle[0] = styleBottleneck
      outputsForPredictContent[0] = contentBottleneck
      preProcessTime = SystemClock.uptimeMillis() - preProcessTime
      // Run for style
      interpreterPredict.runForMultipleInputsOutputs(
        inputsStyleForPredict,
        outputsForPredictStyle
      )
      // Run for blending
      interpreterPredict.runForMultipleInputsOutputs(
        inputsContentForPredict,
        outputsForPredictContent
      )


      // Calculation of style blending
      // # Define content blending ratio between [0..1].
      //# 0.0: 0% style extracts from content image.
      //# 1.0: 100% style extracted from content image.
      //content_blending_ratio = 0.5
      //
      //# Blend the style bottleneck of style image and content image
      //style_bottleneck_blended = content_blending_ratio * style_bottleneck_content \
      //
      //                           + (1 - content_blending_ratio) * style_bottleneck

      // Apply style inheritance by changing values with seekbar integers
      for (i in 0 until contentBottleneck[0][0][0].size) {
        contentBottleneck[0][0][0][i] =
          contentBottleneck[0][0][0][i] * contentBlendingRatio
      }

      for (i in styleBottleneck[0][0][0].indices) {
        styleBottleneck[0][0][0][i] =
          styleBottleneck[0][0][0][i] * (1 - contentBlendingRatio)
      }

      for (i in styleBottleneckBlended[0][0][0].indices) {
        styleBottleneckBlended[0][0][0][i] =
          contentBottleneck[0][0][0][i] + styleBottleneck[0][0][0][i]
      }

      stylePredictTime = SystemClock.uptimeMillis() - stylePredictTime
      Log.i("PREDICT", "Style Predict Time to run: $stylePredictTime")

      fullExecutionTime = SystemClock.uptimeMillis()
      preProcessTime = SystemClock.uptimeMillis()

      //val contentImage = ImageUtils.decodeBitmap(File(contentImagePath))
      val contentArray =
        ImageUtils.bitmapToByteBuffer(
          contentBitmap,
          CONTENT_IMAGE_SIZE,
          CONTENT_IMAGE_SIZE
        )
      val inputsForStyleTransfer = if (useGPU) {
        arrayOf(contentArray, styleBottleneckBlended)
      } else {
        arrayOf(styleBottleneckBlended, contentArray)
      }

      val outputsForStyleTransfer = HashMap<Int, Any>()
      val outputImage =
        Array(1) { Array(CONTENT_IMAGE_SIZE) { Array(CONTENT_IMAGE_SIZE) { FloatArray(3) } } }
      outputsForStyleTransfer[0] = outputImage

      styleTransferTime = SystemClock.uptimeMillis()
      interpreterTransform.runForMultipleInputsOutputs(
        inputsForStyleTransfer,
        outputsForStyleTransfer
      )
      styleTransferTime = SystemClock.uptimeMillis() - styleTransferTime
      Log.i("StyleTransferModelExecutor.TAG", "Style apply Time to run: $styleTransferTime")

      postProcessTime = SystemClock.uptimeMillis()
      val styledImage =
        ImageUtils.convertArrayToBitmap(outputImage, CONTENT_IMAGE_SIZE, CONTENT_IMAGE_SIZE)
      postProcessTime = SystemClock.uptimeMillis() - postProcessTime
      Log.i("StyleTransferModelExecutor.TAG", "Post process time: $postProcessTime")

      fullExecutionTime = SystemClock.uptimeMillis() - fullExecutionTime
      Log.i("STYLE_SOLOUPIS", "Time to run everything: $fullExecutionTime")


      return ModelExecutionResult(
        styledImage,
        preProcessTime,
        stylePredictTime,
        styleTransferTime,
        postProcessTime,
        fullExecutionTime,
        formatExecutionLog()
      )
    } catch (e: Exception) {
      val exceptionLog = "something went wrong: ${e.message}"
      Log.d(TAG, exceptionLog)

      val emptyBitmap =
        ImageUtils.createEmptyBitmap(
          CONTENT_IMAGE_SIZE,
          CONTENT_IMAGE_SIZE
        )
      return ModelExecutionResult(
        emptyBitmap, errorMessage = e.message!!
      )
    }
  }

  @Throws(IOException::class)
  private fun loadModelFile(context: Context, modelFile: String): MappedByteBuffer {
    val fileDescriptor = context.assets.openFd(modelFile)
    val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
    val fileChannel = inputStream.channel
    val startOffset = fileDescriptor.startOffset
    val declaredLength = fileDescriptor.declaredLength
    val retFile = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    fileDescriptor.close()
    return retFile
  }

  @Throws(IOException::class)
  private fun getInterpreter(
    context: Context,
    modelName: String,
    useGpu: Boolean = false
  ): Interpreter {
    val tfliteOptions = Interpreter.Options()
    tfliteOptions.setNumThreads(numberThreads)

    gpuDelegate = null
    if (useGpu) {
      gpuDelegate = GpuDelegate()
      tfliteOptions.addDelegate(gpuDelegate)
    }

    tfliteOptions.setNumThreads(numberThreads)
    return Interpreter(loadModelFile(context, modelName), tfliteOptions)
  }

  private fun formatExecutionLog(): String {
    val sb = StringBuilder()
    sb.append("Input Image Size: $CONTENT_IMAGE_SIZE x $CONTENT_IMAGE_SIZE\n")
    sb.append("GPU enabled: $useGPU\n")
    sb.append("Number of threads: $numberThreads\n")
    sb.append("Pre-process execution time: $preProcessTime ms\n")
    sb.append("Predicting style execution time: $stylePredictTime ms\n")
    sb.append("Transferring style execution time: $styleTransferTime ms\n")
    sb.append("Post-process execution time: $postProcessTime ms\n")
    sb.append("Full execution time: $fullExecutionTime ms\n")
    return sb.toString()
  }

  fun close() {
    interpreterPredict.close()
    interpreterTransform.close()
    if (gpuDelegate != null) {
      gpuDelegate!!.close()
    }
  }
}
