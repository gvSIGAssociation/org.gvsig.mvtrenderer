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

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Collections;
import java.util.logging.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.style.Style;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.FeatureLayer;
import org.geotools.map.MapContent;
import org.geotools.renderer.lite.StreamingRenderer;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

/**
 * Represents a renderable layer composed of features and a style.
 *
 * @author fdiaz
 */
public class MVTLayer {
  private static final Logger LOGGER = Logger.getLogger(MVTLayer.class.getName());

  private final String id;
  private final SimpleFeatureCollection features;
  private final Style style;
//  private Polygon tileExtent;
  private Envelope envelope;

  /**
   * Constructor for data layers.
   *
   * @param id Identifier of the layer (style layer id).
   * @param features Collection of features to render.
   * @param style The GeoTools style to apply.
   */
  public MVTLayer(String id, SimpleFeatureCollection features, Style style, Envelope envelope) {
    this.id = id;
    this.features = features;
    this.style = style;
//    final GeometryFactory geometryFactory = new GeometryFactory();
//    this.tileExtent = geometryFactory.createPolygon(new Coordinate[]{
//      new Coordinate(0, 0),
//      new Coordinate(extent, 0),
//      new Coordinate(extent, extent),
//      new Coordinate(0, extent),
//      new Coordinate(0, 0)
//    });
//    this.envelope = ReferencedEnvelope.reference(tileExtent.getEnvelopeInternal());
    this.envelope = envelope;

  }

  /**
   * Constructor for background layers (no source data).Creates a synthetic
   * feature collection containing the background polygon.
   *
   * @param id Identifier of the layer.
   * @param background The polygon covering the tile extent.
   * @param style The GeoTools style to apply.
   * @param extent
   */
  public MVTLayer(String id, Polygon background, Style style, Envelope env) {
    this(id, createBackgroundCollection(background), style, env);
  }

  /**
   * Renders this layer onto the provided Graphics2D context.
   *
   * @param g2d The graphics context to draw on.
   * @param drawingArea The area of the image being drawn (e.g., new
   * Rectangle(0, 0, width, height)).
   */
  public void render(Graphics2D g2d, Rectangle drawingArea) {
    if (features == null || features.isEmpty() || style == null) {
      return;
    }
    MapContent mapContent = new MapContent();
    try {
      FeatureLayer layer = new FeatureLayer(features, style);
      mapContent.addLayer(layer);

      StreamingRenderer renderer = new StreamingRenderer();
      renderer.setMapContent(mapContent);

      // Crear los hints de suavizado
      RenderingHints hints = new RenderingHints(
              RenderingHints.KEY_ANTIALIASING,
              RenderingHints.VALUE_ANTIALIAS_ON
      );

      // También es recomendable activar el suavizado de texto si tienes etiquetas
      hints.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

      // Aplicarlos al renderizador
      renderer.setJava2DHints(hints);

      renderer.paint(g2d, drawingArea, this.envelope);

    } finally {
      mapContent.dispose();
    }
  }

  private static SimpleFeatureCollection createBackgroundCollection(Polygon background) {
    try {
      // Create a simple feature type
      SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
      typeBuilder.setName("background");
      typeBuilder.add("geometry", Polygon.class);
      // Assuming no specific CRS for raw screen/tile coords, or use EPSG:3857 if known
      // typeBuilder.setCRS(DefaultGeographicCRS.WGS84); 

      SimpleFeatureType featureType = typeBuilder.buildFeatureType();

      // Create the feature
      SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
      featureBuilder.add(background);
      SimpleFeature feature = featureBuilder.buildFeature(null);

      return new ListFeatureCollection(featureType, Collections.singletonList(feature));
    } catch (Exception e) {
      throw new RuntimeException("Failed to create background feature", e);
    }
  }

  public String getId() {
    return id;
  }

}
