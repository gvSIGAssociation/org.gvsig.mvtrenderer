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

import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.MvtReader;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.TagKeyValueMapConverter;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.model.JtsLayer;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.model.JtsMvt;
import java.awt.Graphics2D;
import java.awt.Rectangle;
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
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;

/**
 *
 * @author fdiaz
 */
public class MVTTile {

  private static final Logger LOGGER = Logger.getLogger(MVTTile.class.getName());

  private Map<String, MVTDataSource> sourceLayers = new HashMap<>();
  public boolean debugMode = false;

  private int tileX;
  private int tileY;
  private int tileZ;
  private Integer forcedExtent = null;
  private Envelope envelope;

  public static class MVTDataSource {

    SimpleFeatureCollection features;
    String name;
    Envelope envelope;

    public MVTDataSource(SimpleFeatureCollection features, String name, Envelope envelope) {
      this.features = features;
      this.name = name;
      this.envelope = envelope;
    }

  }

//  public Integer getForcedExtent() {
//    return forcedExtent;
//  }
//
//  public void setForcedExtent(Integer forcedExtent) {
//    this.forcedExtent = forcedExtent;
//  }

  public void download(URL url, Envelope envelope) throws IOException {
    try (InputStream is = url.openStream()) {
      this.download(is, envelope);
    }
  }

  public void download(URL url, int z, int y, int x, Envelope envelope) throws IOException {
    this.tileX = x;
    this.tileY = y;
    this.tileZ = z;
    String s = url.toString().replace("{z}", String.valueOf(this.tileZ));
    s = s.replace("{y}", String.valueOf(this.tileY));
    s = s.replace("{x}", String.valueOf(this.tileX));
    this.download(URI.create(s).toURL(), envelope);
  }

  public void download(InputStream is, Envelope envelope ) throws IOException {
    final GeometryFactory geometryFactory = new GeometryFactory();
    try (PushbackInputStream pbIs = new PushbackInputStream(is, 2)) {
      // Comprobar "Magic Numbers" de GZIP (0x1f, 0x8b)
      byte[] signature = new byte[2];
      int len = pbIs.read(signature);
      pbIs.unread(signature, 0, len); // Devolver bytes al stream

      InputStream finalIs = pbIs;
      if (len == 2 && signature[0] == (byte) 0x1f && signature[1] == (byte) 0x8b) {
        finalIs = new GZIPInputStream(pbIs);
      }
      JtsMvt mvt = MvtReader.loadMvt(finalIs, geometryFactory, new TagKeyValueMapConverter());
      this.envelope = envelope;
      sourceLayers.clear();

      for (JtsLayer layer : mvt.getLayers()) {
        int tileSize = layer.getExtent();
        // If forcedExtent is set, we normalize to that value. 
        // Otherwise we use the native extent from the layer.
//        int targetExtent = (this.forcedExtent != null) ? this.forcedExtent : tileSize;
        double scaleX = envelope.getWidth() / tileSize;
        double scaleY = envelope.getHeight()/ tileSize;

        // Apply transformation to flip Y axis and scale to targetExtent
        AffineTransformation t = new AffineTransformation();
        t.scale(scaleX, -scaleY);
        t.translate(envelope.getMinX(), envelope.getMaxY());

        SimpleFeatureCollection collection = convertToFeatureCollection(layer, t);
        MVTDataSource miLayer = new MVTDataSource(collection, layer.getName(), envelope);
        sourceLayers.put(layer.getName(), miLayer);
      }
    }
  }

  public BufferedImage render(MVTStyles mvtStyle, int widthInPixels, int heightInPixels) {
    BufferedImage image = new BufferedImage(widthInPixels, heightInPixels, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = image.createGraphics();

    try {
      Rectangle drawingArea = new Rectangle(0, 0, widthInPixels, heightInPixels);

      List<MVTLayer> layersToDraw = mvtStyle.getLayersToDraw(sourceLayers, envelope);

      for (MVTLayer layer : layersToDraw) {
        layer.render(g2, drawingArea);
      }
    } finally {
      g2.dispose();
    }

    return image;
  }

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

  private SimpleFeatureCollection convertToFeatureCollection(JtsLayer layer, AffineTransformation t) {
    FileWriter writer = null;
    BufferedWriter csvWriter = null;
    try {
      Set<String> attributeNames = new HashSet<>();
      for (Geometry geom : layer.getGeometries()) {
        Object userData = geom.getUserData();
        if (userData instanceof Map) {
          attributeNames.addAll(((Map<String, Object>) userData).keySet());
        }
      }
      SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
      tb.setName(layer.getName());
      tb.add("geometry", Geometry.class); // GeoTools maneja polimorfismo geométrico
      for (String attr : attributeNames) {
        // Asumimos String por defecto para simplificar, o Object.
        // GeoTools es estricto con los tipos. Object suele funcionar para evaluación laxa.
        tb.add(attr, Object.class);
      }
      SimpleFeatureType type = tb.buildFeatureType();
      if (this.debugMode) {
        saveInfo(layer);
        String folder = getFolder();
        String fname = folder + layer.getName().replace('/', '_').replace(':', '_') + ".csv";
        writer = new FileWriter(fname, false);
        csvWriter = new BufferedWriter(writer, 8096);
        for (AttributeDescriptor attributeDescriptor : type.getAttributeDescriptors()) {
          csvWriter.append("\"");
          csvWriter.append(attributeDescriptor.getLocalName());
          csvWriter.append("\";");
        }
        csvWriter.append("\"geom\"\n");
      }
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
        if (this.debugMode) {
          addRowToCSV(csvWriter, f, geom);
        }
        features.add(f);
      }
      if (this.debugMode) {
        writer.flush();
        csvWriter.flush();
      }
      return new ListFeatureCollection(type, features);
    } catch (IOException ex) {
      LOGGER.log(Level.WARNING, "Can't convert to FeatureCollection", ex);
      return null;
    } finally {
      try {
        IOUtils.close(writer);
        IOUtils.close(csvWriter);
      } catch (IOException ex) {
        LOGGER.log(Level.WARNING, "Can't close csv file", ex);
      }
    }
  }

  private void addRowToCSV(Writer writer, SimpleFeature f, Geometry geom) throws IOException {
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

}
