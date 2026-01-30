/**
 * gvSIG. Desktop Geographic Information System.
 *
 * Copyright (C) 2007-2026 gvSIG Association.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 *
 * For any additional information, do not hesitate to contact us
 * at info AT gvsig.com, or visit our website www.gvsig.com.
 */
package org.gvsig.mvtrenderer.lib.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geotools.api.style.FeatureTypeStyle;
import org.geotools.api.style.Style;
import org.geotools.api.style.StyleFactory;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.mbstyle.MBStyle;
import org.geotools.mbstyle.layer.MBLayer;
import org.geotools.mbstyle.parse.MBObjectStops;
import org.geotools.mbstyle.parse.MBStyleParser;
import org.json.simple.parser.ParseException;

import org.locationtech.jts.geom.Polygon;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.gvsig.mvtrenderer.lib.impl.MVTTile.MVTDataSource;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

/**
 *
 * @author fdiaz
 */
@SuppressWarnings("UseSpecificCatch")
public class MVTStyles {

  private static final Logger LOGGER = Logger.getLogger(MVTStyles.class.getName());

  private static final int BACKGROUND_SIZE = 4096;

  private MBStyle mbStyle;
  private URL url;

  // Caché: ID de capa de estilo -> Objeto Style de GeoTools
  private Map<String, Style> cachedStyles = new HashMap<>();
  private final Polygon background;
  private Collection<String> usedFontNames = Collections.EMPTY_SET;

  public MVTStyles() {
    final GeometryFactory geometryFactory = new GeometryFactory();
    this.background = geometryFactory.createPolygon(new Coordinate[]{
      new Coordinate(0, 0),
      new Coordinate(BACKGROUND_SIZE, 0),
      new Coordinate(BACKGROUND_SIZE, BACKGROUND_SIZE),
      new Coordinate(0, BACKGROUND_SIZE),
      new Coordinate(0, 0)
    });

  }

  /**
   * Descarga el json de estilos y lo parsea con MBStyleParser para comprobar
   * que es correcto. Almacena el resultado en memoria (MBStyle) para su uso
   * posterior.
   *
   * @param url URL del fichero style.json
   * @throws IOException Si hay error de red o de parseo.
   */
  public void download(URL url) throws IOException {
    this.url = url;
    try {
      String jsonContent = readUrl(url);
      MBStyleParser parser = new MBStyleParser();
      // Parseamos y guardamos el objeto MBStyle.
      this.mbStyle = parser.parse(jsonContent);

      // Resolver URL relativa de sprites a absoluta
      String spritePath = (String) this.mbStyle.json.get("sprite");
      if (spritePath != null && !spritePath.startsWith("http")) {
        URL absoluteSpriteUrl = new URL(url, spritePath);
        this.mbStyle.json.put("sprite", absoluteSpriteUrl.toString());
        LOGGER.log(Level.INFO, "Resolved sprite URL to: {0}", absoluteSpriteUrl);
      }

      // Resolver URL relativa de glyphs a absoluta
      String glyphsPath = (String) this.mbStyle.json.get("glyphs");
      if (glyphsPath != null && !glyphsPath.startsWith("http")) {
        URL absoluteGlyphsUrl = new URL(url, glyphsPath);
        this.mbStyle.json.put("glyphs", absoluteGlyphsUrl.toString());
        LOGGER.log(Level.INFO, "Resolved glyphs URL to: {0}", absoluteGlyphsUrl);
      }

      this.cachedStyles.clear();
      this.usedFontNames = getFontNames(jsonContent);

    } catch (ParseException ex) {
      throw new IOException("Error parsing JSON content from " + url, ex);
    } catch (Exception ex) {
      throw new IOException("Invalid Mapbox Style at " + url, ex);
    }
  }

  /**
   * Construye y devuelve la lista de capas (MVTLayer) listas para ser pintadas,
   * en el orden correcto (Z-order) definido por el estilo Mapbox.
   *
   * @param dataSources Mapa de capas de datos disponibles (source-layer ->
   * FeatureCollection).
   * @return Lista ordenada de objetos MVTLayer.
   */
  public List<MVTLayer> getLayersToDraw(Map<String, MVTDataSource> dataSources) {
    if (mbStyle == null) {
      throw new IllegalStateException("Style not loaded. Call download() first.");
    }

    List<MVTLayer> layersToDraw = new ArrayList<>();

    // Recorremos las capas en el orden definido en el estilo.
    for (MBLayer layer : mbStyle.layers()) {
      String styleLayerId = layer.getId();
      String sourceLayerName = layer.getSourceLayer();
      Style style = getStyle(styleLayerId);

      if (style == null) {
        continue;
      }

      if (sourceLayerName == null) {
        // Caso Background: No tiene source-layer asociado.
        // Usamos el polígono de fondo.
        layersToDraw.add(new MVTLayer(styleLayerId, background, style, BACKGROUND_SIZE));

      } else if (dataSources.containsKey(sourceLayerName)) {
        // Caso Capa de Datos: Existe en el estilo y tenemos datos para ella.
        MVTDataSource dataSource = dataSources.get(sourceLayerName);
        SimpleFeatureCollection features = dataSource.features;
        if (!features.isEmpty()) {
          layersToDraw.add(new MVTLayer(styleLayerId, features, style, dataSource.tileSize));
        }
      }
      // Si la capa está en el estilo pero no no tenemos capa de datos en 'dataSource', se omite.
    }
    return layersToDraw;
  }

  /**
   * Calcula y cachea el Style de geotools asociado al styleLayerId indicado y
   * lo devuelve. Implementación Lazy: solo transforma el estilo si no está en
   * caché.
   *
   * @param styleLayerId ID de la capa de estilo (Mapbox layer id).
   * @return El objeto Style de GeoTools listo para renderizar, o null si el ID
   * no existe.
   */
  public Style getStyle(String styleLayerId) {
    if (mbStyle == null) {
      throw new IllegalStateException("Style not loaded. Call download() first.");
    }

    if (cachedStyles.containsKey(styleLayerId)) {
      return cachedStyles.get(styleLayerId);
    }

    MBLayer layer = mbStyle.layer(styleLayerId);
    if (layer == null) {
      LOGGER.log(Level.WARNING, "Style layer ID ''{0}'' not found in style definition.", styleLayerId);
      return null;
    }

    try {
      // Calculamos escalas para la transformación (Mapbox zoom -> OGC Scale Denominator)
      Double minScale = null;
      Double maxScale = null;

      // El zoom máximo define la escala mínima (mayor detalle)
      if (layer.getMaxZoom() != Integer.MAX_VALUE) {
        minScale = MBObjectStops.zoomLevelToScaleDenominator(Math.min(25d, layer.getMaxZoom()));
      }

      // El zoom mínimo define la escala máxima (menor detalle)
      if (layer.getMinZoom() != Integer.MIN_VALUE) {
        maxScale = MBObjectStops.zoomLevelToScaleDenominator(Math.max(-25d, layer.getMinZoom()));
      }

      // Realizamos la transformación
      List<FeatureTypeStyle> ftsList = layer.transform(mbStyle, minScale, maxScale);

      // Empaquetamos en un Style de OGC
      StyleFactory sf = CommonFactoryFinder.getStyleFactory();
      Style geoToolsStyle = sf.createStyle();

      for (FeatureTypeStyle fts : ftsList) {
        geoToolsStyle.featureTypeStyles().add(fts);
      }

      // Guardar en caché y retornar
      cachedStyles.put(styleLayerId, geoToolsStyle);
      return geoToolsStyle;

    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Error transforming layer " + styleLayerId, e);
      return null;
    }
  }

  private String readUrl(URL url) throws IOException {
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Devuelve la coleccion de nombres de fuentes utilizados en el estilo.
   *
   * @return Coleccion de nombres de fuentes.
   */
  public Collection<String> getUsedFontNames() {
    return Collections.unmodifiableCollection(usedFontNames);
  }

  /**
   * Extrae de forma recursiva todos los nombres de fuentes definidos en el
   * atributo "text-font" del JSON de estilos.
   *
   * @param jsonContents Contenido del JSON de estilos.
   * @return Conjunto de nombres de fuentes únicos encontrados.
   */
  private Collection<String> getFontNames(String jsonContents) {
    Set<String> fonts = new HashSet<>();
    try {
      JSONParser parser = new JSONParser();
      JSONObject root = (JSONObject) parser.parse(jsonContents);
      Object layersObj = root.get("layers");

      if (layersObj instanceof JSONArray) {
        JSONArray layers = (JSONArray) layersObj;
        for (Object layerObj : layers) {
          if (layerObj instanceof JSONObject) {
            JSONObject layer = (JSONObject) layerObj;
            JSONObject layout = (JSONObject) layer.get("layout");
            if (layout != null) {
              Object textFontObj = layout.get("text-font");
              if (textFontObj instanceof JSONArray) {
                JSONArray textFonts = (JSONArray) textFontObj;
                for (Object font : textFonts) {
                  if (font instanceof String) {
                    fonts.add((String) font);
                  }
                }
              }
            }
          }
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Error extracting font names from style", e);
    }
    return fonts;
  }
}
