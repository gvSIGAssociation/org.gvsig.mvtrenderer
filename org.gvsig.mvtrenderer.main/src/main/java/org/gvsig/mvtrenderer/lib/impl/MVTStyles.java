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

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.style.FeatureTypeStyle;
import org.geotools.api.style.Style;
import org.geotools.api.style.StyleFactory;
import org.geotools.api.style.Symbolizer;
import org.geotools.api.style.TextSymbolizer;
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
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;

/**
 * Manages Mapbox Vector Tile styles and their conversion to GeoTools styles.
 *
 * @author fdiaz
 */
@SuppressWarnings("UseSpecificCatch")
public class MVTStyles {

  private static final Logger LOGGER = Logger.getLogger(MVTStyles.class.getName());

  private static final Set<String> EXPLICIT_GETTERS = Set.of("get", "has");
  private static final Set<String> IMPLICIT_COMPARISONS = Set.of("==", "!=", ">", ">=", "<", "<=", "in", "!in");
  private static final Set<String> UNARY_OPERATORS = Set.of("downcase", "upcase", "typeof");
  private static final Set<String> NON_ATTRIBUTE_EXPRESSIONS = Set.of("zoom", "geometry-type", "id", "properties", "feature-state");

  private static final int BACKGROUND_SIZE = 4096;

  public MBStyle mbStyle;
  private URL url;

  private final Map<String, Style> cachedStyles = new HashMap<>();
  private final Polygon background;
  private Collection<String> usedFontNames = Collections.EMPTY_SET;
  
  private Map<String, Set<String>> fieldsByLayer;

  /**
   * Initializes a new instance of MVTStyles and creates the background polygon.
   */
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
   * Downloads the style JSON and parses it with MBStyleParser to verify
   * correctness. Stores the result in memory (MBStyle) for later use.
   *
   * @param url The style.json file URL.
   * @throws IOException If there is a network or parsing error.
   */
  public void download(URL url) throws IOException {
    this.url = url;
    try {
      String jsonContent = readUrl(url);
      MBStyleParser parser = new MBStyleParser();
      this.mbStyle = parser.parse(jsonContent);

      // Resolve relative sprite URL to absolute
      String spritePath = (String) this.mbStyle.json.get("sprite");
      if (spritePath != null && !spritePath.startsWith("http")) {
        URL absoluteSpriteUrl = new URL(url, spritePath);
        this.mbStyle.json.put("sprite", absoluteSpriteUrl.toString());
        LOGGER.log(Level.INFO, "Resolved sprite URL to: {0}", absoluteSpriteUrl);
      }

      // Resolve relative glyphs URL to absolute
      String glyphsPath = (String) this.mbStyle.json.get("glyphs");
      if (glyphsPath != null && !glyphsPath.startsWith("http")) {
        URL absoluteGlyphsUrl = new URL(url, glyphsPath);
        this.mbStyle.json.put("glyphs", absoluteGlyphsUrl.toString());
        LOGGER.log(Level.INFO, "Resolved glyphs URL to: {0}", absoluteGlyphsUrl);
      }
      this.fixTextPadding();
      this.cachedStyles.clear();
      this.usedFontNames = getFontNames(jsonContent);
      this.fixFontNames();

    } catch (ParseException ex) {
      throw new IOException("Error parsing JSON content from " + url, ex);
    } catch (Exception ex) {
      throw new IOException("Invalid Mapbox Style at " + url, ex);
    }
  }

  /**
   * Builds and returns the list of layers (MVTLayer) ready to be painted,
   * in the correct order (Z-order) defined by the Mapbox style.
   *
   * @param dataSources Map of available data layers.
   * @param tileEnvelope The envelope of the tile.
   * @param tileCRS The coordinate reference system of the tile.
   * @return Ordered list of MVTLayer objects.
   */
  public List<MVTLayer> getLayersToDraw(Map<String, MVTDataSource> dataSources, Envelope tileEnvelope, CoordinateReferenceSystem tileCRS, boolean enableTextPartials, Double textMaxSizeLimit) {
    if (mbStyle == null) {
      throw new IllegalStateException("Style not loaded. Call download() first.");
    }

    this.fixTextMaxSize(textMaxSizeLimit);

    List<MVTLayer> layersToDraw = new ArrayList<>();

    // Iterate through the style layers in the order defined in the style.
    for (MBLayer layer : mbStyle.layers()) {
      String styleLayerId = layer.getId();
      String sourceLayerName = layer.getSourceLayer();
      JSONObject layout = layer.getLayout();
      if(layout!=null && layout.containsKey("visibility") && StringUtils.equalsIgnoreCase("none",(String)layout.get("visibility"))) {
        continue;
      }
      
      Style style = getStyle(styleLayerId, enableTextPartials);

      if (style == null) {
        continue;
      }

      if (sourceLayerName == null) {
        // No associated source-layer.
        // Use the background polygon.
        layersToDraw.add(new MVTLayer(styleLayerId, background, style, tileEnvelope, tileCRS));

      } else if (dataSources.containsKey(sourceLayerName)) {
        // Exists in the style and we have data for it.
        MVTDataSource dataSource = dataSources.get(sourceLayerName);
        SimpleFeatureCollection features = dataSource.features;
        if (!features.isEmpty()) {
          layersToDraw.add(new MVTLayer(styleLayerId, features, style, dataSource.envelope));
        }
      }
      // If the layer is in the style but we don't have a data layer in 'dataSource', it is omitted.
    }
    return layersToDraw;
  }

  /**
   * Calculates and caches the GeoTools Style associated with the indicated
   * styleLayerId and returns it.
   *
   * @param styleLayerId The style layer ID (Mapbox layer id).
   * @return The GeoTools Style object ready for rendering, or null if the ID
   * doesn't exist.
   */
  public Style getStyle(String styleLayerId, boolean enableTextPartials) {
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
      Double minScale = null;
      Double maxScale = null;

      // Maximum zoom defines the minimum scale (more detail)
      if (layer.getMaxZoom() != Integer.MAX_VALUE) {
        minScale = MBObjectStops.zoomLevelToScaleDenominator(Math.min(25d, layer.getMaxZoom()));
      }

      // Minimum zoom defines the maximum scale (less detail)
      if (layer.getMinZoom() != Integer.MIN_VALUE) {
        maxScale = MBObjectStops.zoomLevelToScaleDenominator(Math.max(-25d, layer.getMinZoom()));
      }

      List<FeatureTypeStyle> ftsList = layer.transform(mbStyle, minScale, maxScale);

      // Package in an OGC Style
      StyleFactory sf = CommonFactoryFinder.getStyleFactory();
      Style geoToolsStyle = sf.createStyle();
      
      for (FeatureTypeStyle fts : ftsList) {
        if (enableTextPartials) {
          if (fts.rules() != null && fts.rules().size() == 1) {
            List<Symbolizer> symbolizers = fts.rules().get(0).symbolizers();
            if (symbolizers != null && symbolizers.size() == 1 && symbolizers.get(0) instanceof TextSymbolizer) {
              symbolizers.get(0).getOptions().put("partials", "true");
            }
          }
        }
        geoToolsStyle.featureTypeStyles().add(fts);
      }

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
   * Returns the collection of font names used in the style.
   *
   * @return Collection of font names.
   */
  public Collection<String> getUsedFontNames() {
    return Collections.unmodifiableCollection(usedFontNames);
  }

  /**
   * Recursively extracts all font names defined in the "text-font" attribute
   * of the style JSON.
   *
   * @param jsonContents The content of the style JSON.
   * @return Set of unique font names found.
   */
  private Collection<String> getFontNames(String jsonContents) {
    Set<String> fonts = new HashSet<>();
    try {
      JSONParser parser = new JSONParser();
      JSONObject root = (JSONObject) parser.parse(jsonContents);
      Object layersObj = root.get("layers");

      if (layersObj instanceof JSONArray layers) {
        for (Object layerObj : layers) {
          if (layerObj instanceof JSONObject layer) {
            JSONObject layout = (JSONObject) layer.get("layout");
            if (layout != null) {
              Object textFontObj = layout.get("text-font");
              if (textFontObj instanceof JSONArray textFonts) {
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

  public void fixTextPadding() {
    try {
      Object layersObj = this.mbStyle.json.get("layers");
      if (layersObj instanceof JSONArray layers) {
        for (Object layerObj : layers) {
          if (layerObj instanceof JSONObject layer) {
            JSONObject layout = (JSONObject) layer.get("layout");
            if (layout != null) {
              Object textPaddingObj = layout.get("text-padding");
              if (textPaddingObj != null && !(textPaddingObj instanceof Number)) {
                LOGGER.log(Level.WARNING, "'text-padding' in layer " + layer.get("id") + " malformed");
                layout.remove("text-padding");
              }
            }
          }
        }
      }
    } catch (Exception ex) {
      LOGGER.log(Level.WARNING, "Can't fix text-padding", ex);
    }
  }
  
  /**
   * Extracts the field names used in the style layers for each source layer.
   *
   * @return A map of source layer names to sets of field names.
   */
  public Map<String, Set<String>> extractFieldsFromStyles() {
    if(fieldsByLayer != null) {
      return fieldsByLayer;
    }
    Map<String, Set<String>> theFieldsByLayer = new HashMap<>();
    Object layersObj = this.mbStyle.json.get("layers");

    if (layersObj instanceof JSONArray layers) {
      Pattern pattern = Pattern.compile("\\{([^}]+)\\}");

      for (Object layerObj : layers) {
        if (layerObj instanceof JSONObject layer) {
          String sourceLayer = (String) layer.get("source-layer");

          if (sourceLayer != null) {
            Set<String> layerFields = theFieldsByLayer.get(sourceLayer);
            if (layerFields == null) {
              layerFields = new HashSet<>();
              theFieldsByLayer.put(sourceLayer, layerFields);
            }
            Object filterObj = layer.get("filter");
            if (filterObj instanceof JSONArray) {
              findAttributesRecursive(filterObj, layerFields);
            }
            JSONObject layout = (JSONObject) layer.get("layout");
            if (layout != null) {
              Object textFieldObj = layout.get("text-field");
              if (textFieldObj instanceof String textField) {
                Matcher matcher = pattern.matcher(textField);

                while (matcher.find()) {
                  layerFields.add(matcher.group(1));
                }
              }
            }
          }
        }
      }
    }
    this.fieldsByLayer = theFieldsByLayer;
    return this.fieldsByLayer;
  }
  
  private void findAttributesRecursive(Object expression, Set<String> attributes) {
    if (!(expression instanceof JSONArray)) {
      return;
    }

    JSONArray exprArray = (JSONArray) expression;
    if (exprArray.isEmpty() || !(exprArray.get(0) instanceof String)) {
      return;
    }

    String operator = (String) exprArray.get(0);

    if (NON_ATTRIBUTE_EXPRESSIONS.contains(operator)) {
      return;
    }

    if (EXPLICIT_GETTERS.contains(operator)) {
      if (exprArray.size()> 1 && exprArray.get(1) instanceof String) {
        attributes.add((String) exprArray.get(1));
      }
      return;
    }

    if (IMPLICIT_COMPARISONS.contains(operator)) {
      String potentialImplicitAttr = null;
      Set<String> explicitAttrsFound = new HashSet<>();

      // Asumir que el segundo elemento, si es un String, es un atributo implícito.
      if (exprArray.size() > 1 && exprArray.get(1) instanceof String) {
        potentialImplicitAttr = (String) exprArray.get(1);
      }

      // Buscar recursivamente atributos explícitos en todos los operandos.
      for (int i = 1; i < exprArray.size(); i++) {
        findAttributesRecursive(exprArray.get(i), explicitAttrsFound);
      }

      // Decidir qué atributos añadir.
      if (explicitAttrsFound.isEmpty()) {
        // Si no se encontraron atributos explícitos, la suposición inicial era correcta.
        if (potentialImplicitAttr != null) {
          attributes.add(potentialImplicitAttr);
        }
      } else {
        // Si se encontraron atributos explícitos, estos tienen prioridad.
        attributes.addAll(explicitAttrsFound);
      }
      return;
    }
    
   if (UNARY_OPERATORS.contains(operator)) {
        if (exprArray.size() > 1 && exprArray.get(1) instanceof String) {
            attributes.add((String) exprArray.get(1));
        }
        return;
    }

    // Comportamiento por defecto para otros operadores (all, any, match, downcase, etc.):
    // simplemente analizar sus argumentos recursivamente.
    for (int i = 1; i < exprArray.size(); i++) {
      findAttributesRecursive(exprArray.get(i), attributes);
    }
  }

  private void fixFontNames() {
    String[] ss = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
    List<String> families = Arrays.asList(ss);
    JSONArray layersObj = (JSONArray) this.mbStyle.json.get("layers");

    try {
      for (Object layerObj : layersObj) {
        if (layerObj instanceof JSONObject layer) {
          JSONObject layout = (JSONObject) layer.get("layout");
          if (layout != null) {
            Object textFontObj = layout.get("text-font");
            if (textFontObj instanceof JSONArray textFonts) {
              for (Object font : textFonts) {
                if (font instanceof String) {
                  if(!families.contains((String)font)) {
                    String translatedFont = this.getTranslatedFont((String) font);
                    JSONArray textFontArray = new JSONArray();
                    textFontArray.add(translatedFont);
                    layout.put("text-font",textFontArray);
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
  }
  
  private String getTranslatedFont(String fontName) {
    String[] ss = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
    List<String> families = Arrays.asList(ss);
    String translatedFontName = null;
    for (String family : families) {
      List<String> x = Arrays.asList(StringUtils.split(family, ' '));
      if(x.contains("Noto")) {
        translatedFontName = "Noto";
        break;
      }
      if(x.contains("DejaVu")) {
        translatedFontName = "DejaVu";
      }
      if(x.contains("Liberation") && (translatedFontName == null || !translatedFontName.equals("DejaVu"))) {
        translatedFontName = "Liberation";
      }
    }
    if(translatedFontName == null) {
      return fontName;
    }
    List<String> x = Arrays.asList(StringUtils.split(fontName.toLowerCase(), ' '));
    if(x.contains("bold")) {
      return translatedFontName +" Sans Bold";
    }
    if(x.contains("italic")) {
      return translatedFontName +" Sans Italic";
    }

    if(x.contains("regular")) {
      return translatedFontName +" Sans Regular";
    }
    return translatedFontName + " Sans";
    
  }
  
  
  private void fixTextMaxSize(Double textMaxSizeLimit ){
    if(textMaxSizeLimit == null) {
      return;
    }
    JSONArray layersObj = (JSONArray) this.mbStyle.json.get("layers");

    try {
      for (Object layerObj : layersObj) {
        if (layerObj instanceof JSONObject layer) {
          JSONObject layout = (JSONObject) layer.get("layout");
          if (layout != null) {
            Object textSize = layout.get("text-size");
            if(!(textSize instanceof JSONArray) && !(textSize instanceof JSONObject)) {
              //MBStyle library accept only "text-max-width" for literal values of "text-size"
              Object symbolPlacementObj = layout.get("symbol-placement");
              if(symbolPlacementObj == null || symbolPlacementObj.toString().trim().equals("point")) {
                layout.put("text-max-width", textMaxSizeLimit);
              }
            }
          }
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Error extracting font names from style", e);
    }
  }
  
  
}
