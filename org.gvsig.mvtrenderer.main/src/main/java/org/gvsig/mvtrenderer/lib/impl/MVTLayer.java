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

import java.util.Collections;
import java.util.logging.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.style.Style;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Envelope;
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
    this.envelope = envelope;

  }

  /**
   * Constructor for background layers (no source data). Creates a synthetic
   * feature collection containing the background polygon.
   *
   * @param id Identifier of the layer.
   * @param background The polygon covering the tile extent.
   * @param style The GeoTools style to apply.
   * @param env The envelope of the layer.
   * @param tileCrs The coordinate reference system of the tile.
   */
  public MVTLayer(String id, Polygon background, Style style, Envelope env, CoordinateReferenceSystem tileCrs) {
    this(id, createBackgroundCollection(background, tileCrs), style, env);
  }
  
  /**
   * Returns the GeoTools style associated with this layer.
   *
   * @return The Style object.
   */
  public Style getStyle() {
    return this.style;
  }
  
  /**
   * Returns the collection of features associated with this layer.
   *
   * @return The SimpleFeatureCollection.
   */
  public SimpleFeatureCollection getFeatures() {
    return this.features;
  }

  private static SimpleFeatureCollection createBackgroundCollection(Polygon background, CoordinateReferenceSystem tileCrs) {
    try {
      // Create a simple feature type
      SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
      typeBuilder.setName("background");
      typeBuilder.add("geometry", Polygon.class);
      if(tileCrs != null) {
        typeBuilder.setCRS(tileCrs); 
      }

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

  /**
   * Returns the identifier of this layer.
   *
   * @return The layer ID.
   */
  public String getId() {
    return id;
  }

}
