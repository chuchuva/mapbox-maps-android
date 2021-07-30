package com.mapbox.maps.extension.androidauto

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.mapbox.common.Logger
import com.mapbox.maps.BuildConfig
import com.mapbox.maps.renderer.Widget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BaseWidgetRenderer(
  private val bitmap: Bitmap,
  private val width: Int,
  private val height: Int
) : Widget() {
  private var program = 0
  private var vertexPositionHandle = 0
  private var texturePositionHandle = 0
  private var textureHandle = 0
  private var screenMatrixHandle = 0
  private val textures = intArrayOf(0)

  //  private var colorHandle = 0
  private var vertexShader = 0
  private var fragmentShader = 0

  init {
    Log.e(TAG, "bitmap: ${bitmap.width}, ${bitmap.height}")
    Log.e(TAG, "screen size: ${width}, ${height}")
  }

  // The uScreen matrix
  //
  // First of all, the only coordinate system that OpenGL understands
  // put the center of the screen at the 0,0 position. The maximum value of
  // the X axis is 1 (rightmost part of the screen) and the minimum is -1
  // (leftmost part of the screen). The same thing goes for the Y axis,
  // where 1 is the top of the screen and -1 the bottom.
  //
  // However, when you're doing a 2d application you often need to think in 'pixels'
  // (or something like that). If you have a 300x300 screen, you want to see the center
  // at 150,150 not 0,0!
  //
  // The solution to this 'problem' is to multiply a matrix with your position to
  // another matrix that will convert 'your' coordinates to the one OpenGL expects.
  // There's no magic in this, only a bit of math. Try to multiply the uScreen matrix
  // to the 150,150 position in a sheet of paper and look at the results.
  //
  // IMPORTANT: When trying to calculate the matrix on paper, you should treat the
  // uScreen ROWS as COLUMNS and vice versa. This happens because OpenGL expect the
  // matrix values ordered in a more efficient way, that unfortunately is different
  // from the mathematical notation :(
  private val screenMatrixData = floatArrayOf(
    2f / width.toFloat(), 0f, 0f, 0f,
    0f, -2f / height.toFloat(), 0f, 0f,
    0f, 0f, 0f, 0f,
    -1f, 1f, 0f, 1f
  )

  private val screenMatrixBuffer: FloatBuffer by lazy {
    // initialize vertex byte buffer for shape coordinates
    // (number of coordinate values * 4 bytes per float)
    ByteBuffer.allocateDirect(screenMatrixData.size * BYTES_PER_FLOAT).run {
      // use the device hardware's native byte order
      order(ByteOrder.nativeOrder())

      // create a floating point buffer from the ByteBuffer
      asFloatBuffer().apply {
        // add the coordinates to the FloatBuffer
        put(screenMatrixData)
        // set the buffer to read the first coordinate
        rewind()
      }
    }
  }

  private val vertexPositionData = floatArrayOf(
    0f, height.toFloat() - bitmap.height.toFloat(),  //V1
    0f, height.toFloat(),  //V2
    bitmap.width.toFloat(), height.toFloat() - bitmap.height.toFloat(), //V3
    bitmap.width.toFloat(), height.toFloat(), //V4
  )

  private val vertexPositionBuffer: FloatBuffer by lazy {
    // initialize vertex byte buffer for shape coordinates
    // (number of coordinate values * 4 bytes per float)
    ByteBuffer.allocateDirect(vertexPositionData.size * BYTES_PER_FLOAT).run {
      // use the device hardware's native byte order
      order(ByteOrder.nativeOrder())

      // create a floating point buffer from the ByteBuffer
      asFloatBuffer().apply {
        // add the coordinates to the FloatBuffer
        put(vertexPositionData)
        // set the buffer to read the first coordinate
        rewind()
      }
    }
  }

  private val texturePositionData = floatArrayOf(
    0f, 0f,     //Texture coordinate for V1
    0f, 1f,
    1f, 0f,
    1f, 1f
  )

  private val texturePositionBuffer: FloatBuffer by lazy {
    // initialize vertex byte buffer for shape coordinates
    // (number of coordinate values * 4 bytes per float)
    ByteBuffer.allocateDirect(texturePositionData.size * BYTES_PER_FLOAT).run {
      // use the device hardware's native byte order
      order(ByteOrder.nativeOrder())

      // create a floating point buffer from the ByteBuffer
      asFloatBuffer().apply {
        // add the coordinates to the FloatBuffer
        put(texturePositionData)
        // set the buffer to read the first coordinate
        rewind()
      }
    }
  }

  override fun initialize() {
    val maxAttrib = IntArray(1)
    GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_ATTRIBS, maxAttrib, 0)
    Logger.e(TAG, "Max vertex attributes: ${maxAttrib[0]}")

    // load and compile shaders
    vertexShader = loadShader(
      GLES20.GL_VERTEX_SHADER,
      VERTEX_SHADER_CODE
    ).also { checkCompileStatus(it) }

    fragmentShader = loadShader(
      GLES20.GL_FRAGMENT_SHADER,
      FRAGMENT_SHADER_CODE
    ).also { checkCompileStatus(it) }

    // create empty OpenGL ES Program
    program = GLES20.glCreateProgram().also {
      checkError("glCreateProgram")
      // add the vertex shader to program
      GLES20.glAttachShader(it, vertexShader).also { checkError("glAttachShader") }

      // add the fragment shader to program
      GLES20.glAttachShader(it, fragmentShader).also { checkError("glAttachShader") }

      // creates OpenGL ES program executables
      GLES20.glLinkProgram(it).also {
        checkError("glLinkProgram")
      }
    }

    // get handle to screen matrix(fragment shader's uScreen member)
    screenMatrixHandle =
      GLES20.glGetUniformLocation(program, "uScreen").also { checkError("glGetAttribLocation") }

    // get handle to vertex shader's vPosition member
    vertexPositionHandle =
      GLES20.glGetAttribLocation(program, "vPosition").also { checkError("glGetAttribLocation") }

    // get handle to texture coordinate(vertex shader's vCoordinate member)
    texturePositionHandle =
      GLES20.glGetAttribLocation(program, "vCoordinate").also { checkError("glGetAttribLocation") }

    // get handle to fragment shader's vColor member
    textureHandle =
      GLES20.glGetUniformLocation(program, "vTexture").also { checkError("glGetAttribLocation") }
  }

  override fun render() {
    super.render()
    if (program != 0) {
      // Add program to OpenGL ES environment
      GLES20.glUseProgram(program).also {
        checkError("glUseProgram")
      }

//      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexPositionHandle)

      GLES20.glUniformMatrix4fv(
        screenMatrixHandle,
        screenMatrixBuffer.limit() / screenMatrixData.size,
        false,
        screenMatrixBuffer
      )

      createTexture()

      // Activate the first texture (GL_TEXTURE0) and bind it to our handle
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);

      // Textures
      GLES20.glUniform1i(textureHandle, 0)

      // set the viewport and a fixed, white background
//      GLES20.glViewport(0, 0, width, height);
      GLES20.glClearColor(1f, 1f, 1f, 1f);

      // since we're using a PNG file with transparency, enable alpha blending.
      GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
      GLES20.glEnable(GLES20.GL_BLEND);

      // Enable a handle to the vertices
      GLES20.glEnableVertexAttribArray(vertexPositionHandle)
        .also { checkError("glEnableVertexAttribArray") }

      // Prepare the vertex coordinate data
      GLES20.glVertexAttribPointer(
        vertexPositionHandle, COORDS_PER_VERTEX,
        GLES20.GL_FLOAT, false,
        VERTEX_STRIDE, vertexPositionBuffer
      ).also { checkError("glVertexAttribPointer") }

//      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texturePositionHandle)

      // Enable a handle to the tex position
      GLES20.glEnableVertexAttribArray(texturePositionHandle)
        .also { checkError("glEnableVertexAttribArray") }

      // Prepare the texture coordinate data
      GLES20.glVertexAttribPointer(
        texturePositionHandle, COORDS_PER_VERTEX,
        GLES20.GL_FLOAT, false,
        VERTEX_STRIDE, texturePositionBuffer
      ).also { checkError("glVertexAttribPointer") }

      // Draw the background
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        .also { checkError("glDrawArrays") }

      // never forget to clean up!

      // Clearing
      GLES20.glDisableVertexAttribArray(vertexPositionHandle)
//      GLES20.glDisableVertexAttribArray(texturePositionHandle)
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
      GLES20.glUseProgram(0)
    }
  }

  fun contextLost() {
    Logger.w(TAG, "contextLost")
    program = 0
  }

  override fun deinitialize() {
    if (program != 0) {
      // Disable vertex array
      GLES20.glDisableVertexAttribArray(vertexPositionHandle)
      GLES20.glDetachShader(program, vertexShader)
      GLES20.glDetachShader(program, fragmentShader)
      GLES20.glDeleteShader(vertexShader)
      GLES20.glDeleteShader(fragmentShader)
      GLES20.glDeleteTextures(textures.size, textures, 0)
      GLES20.glDeleteProgram(program)
      program = 0
    }
  }

  private fun checkCompileStatus(shader: Int) {
    if (BuildConfig.DEBUG) {
      val isCompiled = IntArray(1)
      GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, isCompiled, 0)
      if (isCompiled[0] == GLES20.GL_FALSE) {
        val infoLog = GLES20.glGetShaderInfoLog(program)
        throw RuntimeException("checkCompileStatus error: $infoLog")
      }
    }
  }

  private fun createTexture() {
    if (!bitmap.isRecycled) {
      // generate texture
      GLES20.glGenTextures(1, textures, 0)
      // generate texture
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
      // set the color filter is reduced pixel color closest to the coordinates of a pixel in texture drawn as required
      GLES20.glTexParameterf(
        GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_MIN_FILTER,
        GLES20.GL_NEAREST.toFloat()
      )
      // set the amplification filter using texture coordinates to the nearest number of colors, obtained by the weighted averaging algorithm requires pixel color drawn
      GLES20.glTexParameterf(
        GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_MAG_FILTER,
        GLES20.GL_LINEAR.toFloat()
      )
      // Set the circumferential direction S, texture coordinates taken to [1 / 2n, 1-1 / 2n]. Will never lead to integration and border
      GLES20.glTexParameterf(
        GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_WRAP_S,
        GLES20.GL_CLAMP_TO_EDGE.toFloat()
      )
      // Set the circumferential direction T, taken to texture coordinates [1 / 2n, 1-1 / 2n]. Will never lead to integration and border
      GLES20.glTexParameterf(
        GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_WRAP_T,
        GLES20.GL_CLAMP_TO_EDGE.toFloat()
      )
      // The parameters specified above, generates a 2D texture
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
//      bitmap.recycle()
    }
  }

  companion object {
    private const val TAG = "testtest"

    // number of coordinates per vertex in this array
    private const val COORDS_PER_VERTEX = 2
    private const val BYTES_PER_FLOAT = 4
    private const val VERTEX_STRIDE = COORDS_PER_VERTEX * BYTES_PER_FLOAT // 4 bytes per vertex

    // TODO don't forget to use pixel ratio to avoid stretching
    private val BACKGROUND_COORDINATES = floatArrayOf( // in counterclockwise order:
//      -0.8f, -0.8f,
//      -1.0f, -0.8f,
//      -0.8f, -1.0f,
//      -1.0f, -1.0f
      -1.0f, -1.0f,
      1.0f, -1.0f,
      -1.0f, 1.0f,
      1.0f, 1.0f
    )

    private val VERTEX_COUNT = BACKGROUND_COORDINATES.size / COORDS_PER_VERTEX

//    private val VERTEX_SHADER_CODE = """
//      uniform mat4 uScreen;
//      attribute vec2 vPosition;
//      attribute vec2 vCoordinate;
//      varying vec2 aCoordinate;
//      void main() {
//        gl_Position = vec4(vPosition, 0.0, 1.0);
//        aCoordinate = vCoordinate;
//      }
//    """.trimIndent()
    private val VERTEX_SHADER_CODE = """
      uniform mat4 uScreen;
      attribute vec2 vPosition;
      attribute vec2 vCoordinate;
      varying vec2 aCoordinate;
      void main() {
        aCoordinate = vCoordinate;
        gl_Position = uScreen * vec4(vPosition, 0.0, 1.0);
      }
    """.trimIndent()

    private val FRAGMENT_SHADER_CODE = """
      precision mediump float;
      uniform sampler2D vTexture;
      varying vec2 aCoordinate;
      void main() {
        gl_FragColor = texture2D(vTexture, aCoordinate);
      }
    """.trimIndent()

    private fun loadShader(type: Int, shaderCode: String): Int {
      // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
      // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
      return GLES20.glCreateShader(type).also { shader ->

        // add the source code to the shader and compile it
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
      }
    }

    private fun checkError(cmd: String? = null) {
      if (BuildConfig.DEBUG) {
        when (val error = GLES20.glGetError()) {
          GLES20.GL_NO_ERROR -> {
            Logger.d(TAG, "$cmd -> no error")
          }
          GLES20.GL_INVALID_ENUM -> Logger.e(TAG, "$cmd -> error in gl: GL_INVALID_ENUM")
          GLES20.GL_INVALID_VALUE -> Logger.e(TAG, "$cmd -> error in gl: GL_INVALID_VALUE")
          GLES20.GL_INVALID_OPERATION -> Logger.e(TAG, "$cmd -> error in gl: GL_INVALID_OPERATION")
          GLES20.GL_INVALID_FRAMEBUFFER_OPERATION -> Logger.e(
            TAG,
            "$cmd -> error in gl: GL_INVALID_FRAMEBUFFER_OPERATION"
          )
          GLES20.GL_OUT_OF_MEMORY -> Logger.e(TAG, "$cmd -> error in gl: GL_OUT_OF_MEMORY")
          else -> Logger.e(TAG, "$cmd -> error in gl: $error")
        }
      }
    }
  }
}