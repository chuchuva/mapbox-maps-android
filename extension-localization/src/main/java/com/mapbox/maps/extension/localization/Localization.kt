package com.mapbox.maps.extension.localization

import com.mapbox.common.Logger
import com.mapbox.common.SettingsServiceFactory
import com.mapbox.common.SettingsServiceStorageType
import com.mapbox.maps.StyleObjectInfo
import com.mapbox.maps.extension.style.StyleInterface
import com.mapbox.maps.extension.style.expressions.dsl.generated.get
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.getLayerAs
import com.mapbox.maps.extension.style.sources.generated.VectorSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.logE
import com.mapbox.maps.logI
import java.util.*
import com.mapbox.common.MapboxCommonSettings.LANGUAGE
import com.mapbox.common.MapboxCommonSettings.WORLDVIEW

/**
 * Apply languages to style by locale. It will replace the `["get","name_en"]` expression with a new expression `["get","name_xx"]`,
 * where name_xx is the supported local name related with locale.
 * For example if locale is [Locale.GERMAN], the original expression
 * `["format",["coalesce",["get","name_en"],["get","name"]],{}]` will be replaced by
 * `["format",["coalesce",["get","name_de"],["get","name"]],{}]`
 */
internal fun setMapLanguage(locale: Locale, style: StyleInterface, layerIds: List<String>?) {
  var convertedLocale = "name_${locale.language}"
  if (!isSupportedLanguage(convertedLocale)) {
    logE(TAG, "Locale: $locale is not supported.")
    return
  }

  /**
   * Check if the server side localization is set and Log the error if present.
   * `localizeLabel` method (ie Style localization) will not work with server side localization
   */
  checkServerSideLocalizationSet { isLocalizationSet ->
    if (isLocalizationSet) {
      Logger.e(
        TAG,
        "Style localization will not work when language or worldview is set." +
          " Either remove localizeLabel implementation or remove server side localization implementation " +
          "with SettingsInterface.erase(Language) function"
      )
    }
  }

  style.styleSources
    .forEach { source ->
      style.styleLayers
        .filter { it.type == LAYER_TYPE_SYMBOL }
        .filter { layer ->
          layerIds?.contains(layer.id) ?: true
        }
        .forEach { layer ->
          val symbolLayer = style.getLayerAs<SymbolLayer>(layer.id)
          symbolLayer?.let {
            it.textFieldAsExpression?.let { textFieldExpression ->
              if (BuildConfig.DEBUG) {
                logI(TAG, "Localize layer id: ${it.layerId}")
              }

              if (sourceIsStreetsV8(style, source)) {
                convertedLocale = getLanguageNameV8(locale)
              } else if (sourceIsStreetsV7(style, source)) {
                convertedLocale = getLanguageNameV7(locale)
              }
              convertExpression(convertedLocale, it, textFieldExpression)
            }
          }
        }
    }
}

private fun checkServerSideLocalizationSet(listener: (Boolean) -> Unit) {
  val settingsServiceInterface =
    SettingsServiceFactory.getInstance(SettingsServiceStorageType.NON_PERSISTENT)
  var isLocalizationSet = false
  val expectedLanguageSetting = settingsServiceInterface.has(LANGUAGE)
  expectedLanguageSetting.value?.let { language ->
    isLocalizationSet = !language
  }
  val expectedWorldviewSetting = settingsServiceInterface.has(WORLDVIEW)
  expectedWorldviewSetting.value?.let { worldview ->
    isLocalizationSet = !worldview
  }

  listener.invoke(isLocalizationSet)
}

private fun convertExpression(language: String, layer: SymbolLayer, textField: Expression?) {
  textField?.let {
    val stringExpression: String = it.toJson().replace(
      EXPRESSION_REGEX,
      get(language).toJson()
    ).replace(EXPRESSION_ABBR_REGEX, get(language).toJson())
    if (BuildConfig.DEBUG) {
      logI(TAG, "Localize layer with expression: $stringExpression")
    }
    layer.textField(Expression.fromRaw(stringExpression))
  }
}

private fun sourceIsStreetsV8(style: StyleInterface, source: StyleObjectInfo): Boolean =
  sourceIsType(style, source, STREET_V8)

private fun sourceIsStreetsV7(style: StyleInterface, source: StyleObjectInfo): Boolean =
  sourceIsType(style, source, STREET_V7)

private fun sourceIsType(style: StyleInterface, source: StyleObjectInfo, type: String): Boolean {
  if (source.type == SOURCE_TYPE_VECTOR) {
    style.getSourceAs<VectorSource>(source.id)?.url?.let {
      return it.contains(type)
    }
  }
  return false
}

private const val TAG = "LocalizationPluginImpl"
private const val SOURCE_TYPE_VECTOR = "vector"
private const val LAYER_TYPE_SYMBOL = "symbol"
private const val STREET_V7 = "mapbox.mapbox-streets-v7"
private const val STREET_V8 = "mapbox.mapbox-streets-v8"
private val EXPRESSION_REGEX = Regex("\\[\"get\",\\s*\"(name_.{2,7})\"\\]")
private val EXPRESSION_ABBR_REGEX = Regex("\\[\"get\",\\s*\"abbr\"\\]")
private const val LOCALIZATION_THREAD = "localization_thread"