/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.gvsig.mvtrenderer.lib.impl;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import org.geotools.renderer.RenderListener;
import org.geotools.renderer.lite.StreamingRenderer;
import org.locationtech.jts.geom.Polygon;

/**
 * Represents a renderable layer composed of features and a style.
 *
 * @author fdiaz
 */
public class MVTLayer {

    private final String id;
    private final SimpleFeatureCollection features;
    private final Style style;

    /**
     * Constructor for data layers.
     * 
     * @param id Identifier of the layer (style layer id).
     * @param features Collection of features to render.
     * @param style The GeoTools style to apply.
     */
    public MVTLayer(String id, SimpleFeatureCollection features, Style style) {
        this.id = id;
        this.features = features;
        this.style = style;
    }

    /**
     * Constructor for background layers (no source data).
     * Creates a synthetic feature collection containing the background polygon.
     * 
     * @param id Identifier of the layer.
     * @param background The polygon covering the tile extent.
     * @param style The GeoTools style to apply.
     */
    public MVTLayer(String id, Polygon background, Style style) {
        this.id = id;
        this.style = style;
        this.features = createBackgroundCollection(background);
    }

    /**
     * Renders this layer onto the provided Graphics2D context.
     * 
     * @param g2d The graphics context to draw on.
     * @param drawingArea The area of the image being drawn (e.g., new Rectangle(0, 0, width, height)).
     */
    public void render(Graphics2D g2d, Rectangle drawingArea, ReferencedEnvelope mapArea) {
        if (features == null || features.isEmpty() || style == null) {
            return;
        }

        MapContent mapContent = new MapContent();
        try {
          for (int retry = 0; retry < 2; retry++) {
            
            FeatureLayer layer = new FeatureLayer(features, style);
            mapContent.addLayer(layer);

            StreamingRenderer renderer = new StreamingRenderer();
            renderer.setMapContent(mapContent);
            
            List<String> missingAttributes = new ArrayList();
                      
            renderer.addRenderListener(new RenderListener() {
              @Override
              public void featureRenderer(SimpleFeature sf) {
              }

              @Override
              public void errorOccurred(Exception e) {
                System.out.println("###### "+e.getMessage());
                collectMissingAttributes(missingAttributes, e);
              }

            });
            renderer.paint(g2d, drawingArea, mapArea);
            if(missingAttributes.isEmpty()) {
              break;
            }
            rebuildLayer(missingAttributes);
          }
            
        } finally {
            mapContent.dispose();
        }
    }

    private void collectMissingAttributes(List<String> missingAttributes, Exception e) {
    }

    private void rebuildLayer(List<String> missingAttributes) {
    }
    
    private SimpleFeatureCollection createBackgroundCollection(Polygon background) {
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
