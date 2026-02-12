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

import com.google.common.collect.Multimap;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.MvtReader;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.TagKeyValueMapConverter;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.model.JtsLayer;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.model.JtsMvt;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.apache.commons.io.IOUtils;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.filter.function.EnvFunction;
import org.geotools.map.FeatureLayer;
import org.geotools.map.MapContent;
import org.geotools.renderer.lite.StreamingRenderer;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;

/**
 * Represents a Mapbox Vector Tile and provides methods to download, parse, and render it.
 *
 * @author fdiaz
 */
public class MVTTile {

  private static final Logger LOGGER = Logger.getLogger(MVTTile.class.getName());

  private final Map<String, MVTDataSource> sourceLayers = new HashMap<>();
  public boolean debugMode = false;

  private int tileX;
  private int tileY;
  private int tileZ;
  private Envelope envelope;
  private CoordinateReferenceSystem tileCRS;
  private CoordinateReferenceSystem mapCRS;
  private boolean enableTextPartials;
  private boolean assignScaleDenominator;
  private Double textMaxSizeLimit;
  private boolean showTileLimits;

  /**
   * Default constructor. Only for test.
   */
  public MVTTile() {
    enableTextPartials = false;
    assignScaleDenominator = true;
    textMaxSizeLimit = null;
    showTileLimits = false;
  }
  
  /**
   * Constructor with CRS information.
   *
   * @param tileCRS The coordinate reference system of the tile.
   * @param mapCRS The coordinate reference system of the map.
   */
  public MVTTile(CoordinateReferenceSystem tileCRS, CoordinateReferenceSystem mapCRS) {
    this();
    if( tileCRS != null && mapCRS != null ) {
      this.tileCRS = tileCRS;
      this.mapCRS = mapCRS;
    }
  }
  
  /**
   * Data structure to hold a collection of features for a specific source layer.
   */
  public static class MVTDataSource {

    SimpleFeatureCollection features;
    String name;
    Envelope envelope;

    /**
     * Constructs a new MVTDataSource.
     *
     * @param features The collection of features.
     * @param name The name of the source layer.
     * @param envelope The envelope of the layer.
     */
    public MVTDataSource(SimpleFeatureCollection features, String name, Envelope envelope) {
      this.features = features;
      this.name = name;
      this.envelope = envelope;
    }

  }

  public void setEnableTextPartials(boolean enableTextPartials) {
    this.enableTextPartials = enableTextPartials;
  }

  public boolean isEnableTextPartials() {
    return enableTextPartials;
  }

  public void setAssignScaleDenominator(boolean assignScaleDenominator) {
    this.assignScaleDenominator = assignScaleDenominator;
  }

  public boolean isAssignScaleDenominator() {
    return assignScaleDenominator;
  }

  public void setTextMaxSizeLimit(Double textMaxSizeLimit) {
    this.textMaxSizeLimit = textMaxSizeLimit;
  }

  public Double getTextMaxSizeLimit() {
    return textMaxSizeLimit;
  }
  
  public void setParams(Map<String, String> params) {
    if(params == null || params.isEmpty()) {
      return;
    } 
    String x = params.get("textMaxSizeLimit");
    if(x != null) {
      this.textMaxSizeLimit = Double.valueOf(x);
    }
    x = params.get("enableTextPartials");
    if(x != null) {
      this.enableTextPartials = Boolean.parseBoolean(x);
    }
    x = params.get("assignScaleDenominator");
    if(x != null) {
      this.assignScaleDenominator = Boolean.parseBoolean(x);
    }
    x = params.get("showTileLimits");
    if(x != null) {
      this.showTileLimits = Boolean.parseBoolean(x);
    }
  }

  
  /**
   * Downloads and parses a tile from the given URL.
   *
   * @param url The URL to download the tile from.
   * @param envelope The envelope of the tile.
   * @param fieldsByLayer A map of field names to add for each layer.
   * @throws IOException If an I/O error occurs.
   */
  public void download(URL url, Envelope envelope, Map<String, Set<String>> fieldsByLayer) throws IOException {
    try (InputStream is = url.openStream()) {
      this.download(is, envelope, fieldsByLayer);
    }
  }

  /**
   * Downloads and parses a tile from a template URL by replacing {z}, {x}, and {y} placeholders.
   *
   * @param url The template URL.
   * @param z The zoom level.
   * @param y The tile Y coordinate.
   * @param x The tile X coordinate.
   * @param envelope The envelope of the tile.
   * @param fieldsByLayer A map of field names to add for each layer.
   * @throws IOException If an I/O error occurs.
   */
  public void download(URL url, int z, int y, int x, Envelope envelope, Map<String, Set<String>> fieldsByLayer) throws IOException {
    this.tileX = x;
    this.tileY = y;
    this.tileZ = z;
    String s = url.toString().replace("{z}", String.valueOf(this.tileZ));
    s = s.replace("{y}", String.valueOf(this.tileY));
    s = s.replace("{x}", String.valueOf(this.tileX));
    this.download(URI.create(s).toURL(), envelope, fieldsByLayer);
  }

  /**
   * Parses a tile from an input stream. Handles GZIP compression automatically.
   *
   * @param is The input stream containing the tile data.
   * @param envelope The envelope of the tile.
   * @param fieldsByLayer A map of field names to add for each layer.
   * @throws IOException If an I/O error occurs.
   */
  public void download(InputStream is, Envelope envelope, Map<String, Set<String>> fieldsByLayer) throws IOException {
    final GeometryFactory geometryFactory = new GeometryFactory();
    try (PushbackInputStream pbIs = new PushbackInputStream(is, 2)) {
      
      // Check for GZIP "Magic Numbers" (0x1f, 0x8b)
      byte[] signature = new byte[2];
      int len = pbIs.read(signature);
      pbIs.unread(signature, 0, len); 

      InputStream finalIs = pbIs;
      if (len == 2 && signature[0] == (byte) 0x1f && signature[1] == (byte) 0x8b) {
        finalIs = new GZIPInputStream(pbIs);
      }
      
      JtsMvt mvt = MvtReader.loadMvt(finalIs, geometryFactory, new TagKeyValueMapConverter());
      this.envelope = envelope;
      this.sourceLayers.clear();

      for (JtsLayer layer : mvt.getLayers()) {
        int tileSize = layer.getExtent();
        double scaleX = envelope.getWidth() / tileSize;
        double scaleY = envelope.getHeight()/ tileSize;

        AffineTransformation t = new AffineTransformation();
        t.scale(scaleX, -scaleY);
        t.translate(envelope.getMinX(), envelope.getMaxY());

        Set<String> fields = fieldsByLayer.get(layer.getName());
        SimpleFeatureCollection collection = convertToFeatureCollection(layer, fields, t);
        MVTDataSource theLayer = new MVTDataSource(collection, layer.getName(), envelope);
        
        this.sourceLayers.put(layer.getName(), theLayer);
      }
    }
  }

  /**
   * Renders the tile to a BufferedImage using the provided style.
   *
   * @param mvtStyle The MVT style definition.
   * @param widthInPixels The width of the output image in pixels.
   * @param heightInPixels The height of the output image in pixels.
   * @return A BufferedImage containing the rendered tile.
   */
  public BufferedImage render(MVTStyles mvtStyle, int widthInPixels, int heightInPixels) {
    BufferedImage image = new BufferedImage(widthInPixels, heightInPixels, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = image.createGraphics();

    Object previousWmsScaleDenominator = null;
    boolean hasWmsScaleDenominator = false;
    MapContent mapContent = null;
    try {
      Rectangle drawingArea = new Rectangle(0, 0, widthInPixels, heightInPixels);

      List<MVTLayer> layersToDraw = mvtStyle.getLayersToDraw(sourceLayers, envelope, this.tileCRS, this.enableTextPartials, this.textMaxSizeLimit);
      mapContent = new MapContent();
      if( this.mapCRS != null ) {
        mapContent.getViewport().setCoordinateReferenceSystem(this.mapCRS);
      }
      StreamingRenderer renderer = new StreamingRenderer();
      renderer.setMapContent(mapContent);
      // Create smoothing hints
      RenderingHints hints = new RenderingHints(
              RenderingHints.KEY_ANTIALIASING,
              RenderingHints.VALUE_ANTIALIAS_ON
      );
      // Also recommended to enable text smoothing if labels are present
      hints.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

      // Apply hints to the renderer
      renderer.setJava2DHints(hints);

      for (MVTLayer layer : layersToDraw) {
          FeatureLayer featureLayer = new FeatureLayer(layer.getFeatures(), layer.getStyle(), layer.getId());
          mapContent.addLayer(featureLayer);
      }

      Map<String, Object> envLocalValues = EnvFunction.getLocalValues();
      previousWmsScaleDenominator = envLocalValues.get("wms_scale_denominator");
      hasWmsScaleDenominator = envLocalValues.containsKey("wms_scale_denominator");

      if(this.isAssignScaleDenominator()) {
        
        double scaleDenominator = org.geotools.mbstyle.parse.MBObjectStops.zoomLevelToScaleDenominator((double) this.tileZ);
        EnvFunction.setLocalValue("wms_scale_denominator", scaleDenominator);
      }

      renderer.paint(g2, drawingArea, envelope);
      
      if(this.showTileLimits) {
        g2.setColor(Color.red);
        BasicStroke stroke = new BasicStroke(1);
        g2.setStroke(stroke);
        g2.drawRect(0, 0, widthInPixels-1, heightInPixels-1);
      }
      
    } finally {
      if(this.isAssignScaleDenominator()) {
        if(hasWmsScaleDenominator) {
          EnvFunction.setLocalValue("wms_scale_denominator", previousWmsScaleDenominator);
        } else {
          EnvFunction.removeLocalValue("wms_scale_denominator");

        }
      }
      g2.dispose();
      if(mapContent != null) {
        mapContent.dispose();
      }
    }

    return image;
  }

  private SimpleFeatureCollection convertToFeatureCollection(JtsLayer layer, Set<String> fieldNames,AffineTransformation t) {
    Writer[] writers = null;
    try {
      Set<String> attributeNames = new HashSet<>();
      if(fieldNames != null) {
        attributeNames.addAll(fieldNames);
      }
      for (Geometry geom : layer.getGeometries()) {
        Object userData = geom.getUserData();
        if (userData instanceof Map) {
          attributeNames.addAll(((Map<String, Object>) userData).keySet());
        }
      }
      SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
      tb.setName(layer.getName());
      tb.add("geometry", Geometry.class); // GeoTools handles geometric polymorphism
      for (String attr : attributeNames) {
        tb.add(attr, Object.class);
      }
      if( this.tileCRS!=null ) {
        tb.setCRS(this.tileCRS);
      }
      SimpleFeatureType type = tb.buildFeatureType();
      writers = prepareCSV(layer, type);
      
      List<SimpleFeature> features = new ArrayList<>();
      SimpleFeatureBuilder fb = new SimpleFeatureBuilder(type);
      for (Geometry geom : layer.getGeometries()) {
        fb.set("geometry", t.transform(geom));

        Object userData = geom.getUserData();
        if (userData instanceof Map) {
          Map<String, Object> attributes = (Map<String, Object>) userData;
          for (String attr : attributeNames) {
            Object val = attributes.get(attr);
            if (val != null) {
              fb.set(attr, val);
            }
          }
        }
        SimpleFeature f = fb.buildFeature(null);
        addRowToCSV(writers, f, geom);
        features.add(f);
      }
      return new ListFeatureCollection(type, features);
    } catch (IOException ex) {
      LOGGER.log(Level.WARNING, "Can't convert to FeatureCollection", ex);
      return null;
    } finally {
      closeCSV(writers);
    }
  }

  /* 
  =========================================================
  The following methods are only for debugging from main   
  =========================================================
  */
  private Writer[] prepareCSV(JtsLayer layer, SimpleFeatureType type) {
    if (!this.debugMode) {
      return null;
    }
    FileWriter fileWriter = null;
    BufferedWriter csvWriter = null;
    try {
      saveInfo(layer);
      String folder = getFolder();
      String fname = folder + layer.getName().replace('/', '_').replace(':', '_') + ".csv";
      fileWriter = new FileWriter(fname, false);
      csvWriter = new BufferedWriter(fileWriter, 8096);
      for (AttributeDescriptor attributeDescriptor : type.getAttributeDescriptors()) {
        csvWriter.append("\"");
        csvWriter.append(attributeDescriptor.getLocalName());
        csvWriter.append("\";");
      }
      csvWriter.append("\"geom\"\n");    
      return new Writer[] {fileWriter, csvWriter}; 
    } catch(Exception ex) {
      IOUtils.closeQuietly(csvWriter);
      IOUtils.closeQuietly(fileWriter);
      return null;
    }
  }
  
  private void addRowToCSV(Writer[] writers, SimpleFeature f, Geometry geom) throws IOException {
    if (!this.debugMode) {
      return;
    }
    Writer writer = writers[1];
    SimpleFeatureType type = f.getFeatureType();
    for (AttributeDescriptor attributeDescriptor : type.getAttributeDescriptors()) {
      writer.append("\"");
      writer.append(Objects.toString(f.getAttribute(attributeDescriptor.getLocalName())));
      writer.append("\";");
    }
    writer.append("\"");
    writer.append(geom.toText());
    writer.append("\"\n");
  }

  private void closeCSV(Writer[] writers) {
    if (!this.debugMode) {
      return;
    }
    try {
      writers[0].flush();
      writers[1].flush();
      IOUtils.close(writers[0]);
      IOUtils.close(writers[1]);
    } catch (IOException ex) {
      LOGGER.log(Level.INFO, "Can't close debug CSV files", ex);
    }
  }

  /**
   * Returns the folder path where tile data is stored. Creates the folder if it doesn't exist.
   * Only for test (see main).
   *
   * @return The folder path string.
   */
  public String getFolder() {
    String s = "../tmp/tiles/tile_" + this.tileZ + "_" + this.tileY + "_" + this.tileX + "/";
    File f = new File(s);
    try {
      f.mkdirs();
      f.mkdir();
    } catch (Exception ex) {

    }
    return s;
  }

  private void saveInfo(JtsLayer layer) {
    FileReader reader = null;
    FileWriter writer = null;
    try {
      Properties props = new Properties();
      String filename = getFolder() + "tile.properties";
      File file = new File(filename);
      if (file.exists()) {
        reader = new FileReader(file);
        props.load(reader);
      }
      props.setProperty(layer.getName() + ".features", String.valueOf(layer.getGeometries().size()));
      props.setProperty(layer.getName() + ".extent", String.valueOf(layer.getExtent()));
      writer = new FileWriter(file);
      props.store(writer, "");
    } catch (Exception ex) {
      LOGGER.log(Level.WARNING, "Can't save tile information", ex);
    } finally {
      IOUtils.closeQuietly(reader);
      IOUtils.closeQuietly(writer);
    }
  }
 
}