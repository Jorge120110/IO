package com.example.georutassos.presentation.poc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.georutassos.R;
import com.example.georutassos.data.local.entity.GraphEdgeEntity;
import com.example.georutassos.data.local.entity.GraphNodeEntity;
import com.example.georutassos.domain.model.GeoPoint;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.JointType;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.RoundCap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Contenedor de Mapa de Google Maps SDK ({@link MapView} + {@link GoogleMap}) para GeoRuta SOS.
 * Sin emojis en marcadores ni encabezados, y con soporte para centrar la vista relativa
 * a la ubicacion precisa actual del usuario.
 */
public class RouteMapCanvasView extends FrameLayout implements OnMapReadyCallback {

    public record StreetSegment(double lat1, double lng1, double lat2, double lng2) {}

    @FunctionalInterface
    public interface OnMapCoordinatePickedListener {
        void onCoordinatePicked(@NonNull GeoPoint pickedPoint);
    }

    private static final LatLng DEFAULT_GUATEMALA_CENTER = new LatLng(14.6277121, -90.5602439);
    private static final float DEFAULT_ZOOM = 14.5f;

    private MapView googleMapView;
    @Nullable
    private GoogleMap googleMap;
    private TextView hudOverlayBanner;

    @Nullable
    private Marker stationMarker;
    @Nullable
    private Marker destinationMarker;
    @Nullable
    private Marker agentMarker;
    @Nullable
    private Circle agentToleranceCircle;
    @Nullable
    private Polyline activeGooglePolyline;

    private List<GeoPoint> activePolylinePoints = Collections.emptyList();
    @Nullable
    private GeoPoint stationLocation = null;
    @NonNull
    private String stationLabel = "ESTACION BASE";

    @Nullable
    private GeoPoint destinationLocation = null;
    @NonNull
    private String destinationLabel = "PUNTO DE EMERGENCIA";

    @Nullable
    private GeoPoint agentLocation = null;
    @Nullable
    private GeoPoint userRelativeCenterLocation = null;

    private boolean myLocationPermissionGranted = false;
    private boolean isRecalculatedRoute = false;
    private boolean shouldAutoFrameCamera = true;
    private boolean mapInitialized = false;
    private String mapTitle = "Google Maps - GeoRuta SOS";
    private String interactionHint = "Toque el mapa para seleccionar ubicacion";

    @Nullable
    private OnMapCoordinatePickedListener coordinatePickedListener;

    public RouteMapCanvasView(@NonNull Context context) {
        super(context);
        initGoogleMapView(context);
    }

    public RouteMapCanvasView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initGoogleMapView(context);
    }

    private void initGoogleMapView(@NonNull Context context) {
        hudOverlayBanner = new TextView(context);
        hudOverlayBanner.setBackgroundColor(Color.parseColor("#CC0F172A"));
        hudOverlayBanner.setTextColor(Color.WHITE);
        hudOverlayBanner.setTextSize(10.5f);
        hudOverlayBanner.setPadding(16, 8, 16, 8);
        LayoutParams bannerParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bannerParams.gravity = Gravity.TOP;
        addView(hudOverlayBanner, bannerParams);
        refreshHudBannerText();
    }

    public void ensureMapViewInitialized() {
        if (mapInitialized) {
            return;
        }
        mapInitialized = true;
        Context context = getContext();
        try {
            MapsInitializer.initialize(context.getApplicationContext());
        } catch (Throwable ignored) {
        }

        googleMapView = new MapView(context);
        LayoutParams mapParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        addView(googleMapView, 0, mapParams);

        try {
            googleMapView.onCreate(null);
            googleMapView.onStart();
            googleMapView.onResume();
            googleMapView.getMapAsync(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (googleMapView != null) {
            googleMapView.onStart();
            googleMapView.onResume();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (googleMapView != null) {
            googleMapView.onPause();
            googleMapView.onStop();
        }
        super.onDetachedFromWindow();
    }

    public void onHostResume() {
        if (googleMapView != null) {
            try {
                googleMapView.onStart();
                googleMapView.onResume();
            } catch (Throwable ignored) {
            }
        }
    }

    public void onHostPause() {
        if (googleMapView != null) {
            try {
                googleMapView.onPause();
            } catch (Throwable ignored) {
            }
        }
    }

    public void onHostDestroy() {
        if (googleMapView != null) {
            try {
                googleMapView.onDestroy();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (getParent() != null) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        this.googleMap = map;
        map.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setAllGesturesEnabled(true);

        applyMyLocationSettingIfPermitted();

        map.setOnMapClickListener(latLng -> {
            if (coordinatePickedListener != null) {
                coordinatePickedListener.onCoordinatePicked(
                        new GeoPoint(latLng.latitude, latLng.longitude)
                );
            }
        });

        LatLng initialCenter = userRelativeCenterLocation != null
                ? new LatLng(userRelativeCenterLocation.latitude(), userRelativeCenterLocation.longitude())
                : DEFAULT_GUATEMALA_CENTER;
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(initialCenter, DEFAULT_ZOOM));
        syncMapOverlays(true);
    }

    @SuppressLint("MissingPermission")
    public void setMyLocationEnabledWithPermission(boolean granted, @Nullable GeoPoint currentDeviceLocation) {
        this.myLocationPermissionGranted = granted;
        if (currentDeviceLocation != null) {
            this.userRelativeCenterLocation = currentDeviceLocation;
        }
        applyMyLocationSettingIfPermitted();
        if (currentDeviceLocation != null
                && googleMap != null
                && activePolylinePoints.isEmpty()
                && stationLocation == null
                && destinationLocation == null) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(currentDeviceLocation.latitude(), currentDeviceLocation.longitude()),
                    15.0f
            ));
        }
    }

    @SuppressLint("MissingPermission")
    private void applyMyLocationSettingIfPermitted() {
        if (googleMap == null) {
            return;
        }
        try {
            googleMap.setMyLocationEnabled(myLocationPermissionGranted);
            googleMap.getUiSettings().setMyLocationButtonEnabled(myLocationPermissionGranted);
        } catch (SecurityException ignored) {
        }
    }

    public void centerCameraRelativeTo(@NonNull GeoPoint point, float zoomLevel) {
        this.userRelativeCenterLocation = point;
        if (googleMap != null) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(point.latitude(), point.longitude()),
                    zoomLevel
            ));
        }
    }

    public void setOnMapCoordinatePickedListener(@Nullable OnMapCoordinatePickedListener listener) {
        this.coordinatePickedListener = listener;
    }

    public void setMapHeader(@NonNull String title, @NonNull String hint) {
        this.mapTitle = title;
        this.interactionHint = hint;
        refreshHudBannerText();
    }

    public void setTotalDatabaseNodes(int count) {
        refreshHudBannerText();
    }

    public void setStreetSegments(@NonNull List<StreetSegment> segments) {
    }

    public void setGraphData(List<GraphNodeEntity> nodes, List<GraphEdgeEntity> edges) {
        refreshHudBannerText();
    }

    public void setStationLocation(@Nullable GeoPoint location, @Nullable String label) {
        this.stationLocation = location;
        if (label != null && !label.isBlank()) {
            this.stationLabel = label;
        }
        syncMapOverlays(true);
    }

    public void setDestinationLocation(@Nullable GeoPoint location, @Nullable String label) {
        this.destinationLocation = location;
        if (label != null && !label.isBlank()) {
            this.destinationLabel = label;
        }
        syncMapOverlays(true);
    }

    public void updateRouteState(
            @NonNull List<GeoPoint> polyline,
            @Nullable GeoPoint currentAgentLocation,
            boolean wasRecalculated
    ) {
        boolean routeSizeChangedSignificantly = Math.abs(
                (polyline != null ? polyline.size() : 0) - this.activePolylinePoints.size()
        ) > 5 || this.activePolylinePoints.isEmpty();

        this.activePolylinePoints = polyline != null ? new ArrayList<>(polyline) : Collections.emptyList();
        this.agentLocation = currentAgentLocation;
        this.isRecalculatedRoute = wasRecalculated;

        if (!this.activePolylinePoints.isEmpty()) {
            this.destinationLocation = this.activePolylinePoints.get(this.activePolylinePoints.size() - 1);
        }

        syncMapOverlays(routeSizeChangedSignificantly);
        refreshHudBannerText();
    }

    public void zoomBy(double factor) {
        if (googleMap == null) {
            return;
        }
        if (factor < 1.0) {
            googleMap.animateCamera(CameraUpdateFactory.zoomIn());
        } else {
            googleMap.animateCamera(CameraUpdateFactory.zoomOut());
        }
    }

    public void resetZoom() {
        this.shouldAutoFrameCamera = true;
        frameCameraToActiveElements(true);
    }

    private void syncMapOverlays(boolean reframeCamera) {
        if (googleMap == null) {
            return;
        }

        // 1. Marcador de Estacion Base (Pin Verde)
        if (stationLocation != null) {
            LatLng stationLatLng = new LatLng(stationLocation.latitude(), stationLocation.longitude());
            String snippet = String.format(
                    Locale.US,
                    "Lat: %.5f, Lng: %.5f",
                    stationLocation.latitude(),
                    stationLocation.longitude()
            );
            if (stationMarker == null) {
                stationMarker = googleMap.addMarker(new MarkerOptions()
                        .position(stationLatLng)
                        .title("ESTACION: " + stationLabel)
                        .snippet(snippet)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
            } else {
                stationMarker.setPosition(stationLatLng);
                stationMarker.setTitle("ESTACION: " + stationLabel);
                stationMarker.setSnippet(snippet);
            }
        } else if (stationMarker != null) {
            stationMarker.remove();
            stationMarker = null;
        }

        // 2. Marcador de Destino de Emergencia (Pin Rojo)
        if (destinationLocation != null) {
            LatLng destLatLng = new LatLng(destinationLocation.latitude(), destinationLocation.longitude());
            String snippet = String.format(
                    Locale.US,
                    "Lat: %.5f, Lng: %.5f",
                    destinationLocation.latitude(),
                    destinationLocation.longitude()
            );
            if (destinationMarker == null) {
                destinationMarker = googleMap.addMarker(new MarkerOptions()
                        .position(destLatLng)
                        .title("EMERGENCIA: " + destinationLabel)
                        .snippet(snippet)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            } else {
                destinationMarker.setPosition(destLatLng);
                destinationMarker.setTitle("EMERGENCIA: " + destinationLabel);
                destinationMarker.setSnippet(snippet);
            }
        } else if (destinationMarker != null) {
            destinationMarker.remove();
            destinationMarker = null;
        }

        // 3. Polyline dinamica de Dijkstra sobre Google Maps
        if (activePolylinePoints != null && activePolylinePoints.size() >= 2) {
            List<LatLng> googleLatLngs = new ArrayList<>(activePolylinePoints.size());
            for (GeoPoint pt : activePolylinePoints) {
                googleLatLngs.add(new LatLng(pt.latitude(), pt.longitude()));
            }
            int routeColor = isRecalculatedRoute
                    ? Color.parseColor("#EA580C")
                    : Color.parseColor("#16A34A");

            if (activeGooglePolyline == null) {
                activeGooglePolyline = googleMap.addPolyline(new PolylineOptions()
                        .addAll(googleLatLngs)
                        .width(14f)
                        .color(routeColor)
                        .geodesic(true)
                        .jointType(JointType.ROUND)
                        .startCap(new RoundCap())
                        .endCap(new RoundCap()));
            } else {
                activeGooglePolyline.setPoints(googleLatLngs);
                activeGooglePolyline.setColor(routeColor);
            }
        } else if (activeGooglePolyline != null) {
            activeGooglePolyline.remove();
            activeGooglePolyline = null;
        }

        // 4. Marcador GPS en tiempo real del Agente (Pin Celeste + Halo de 35m)
        if (agentLocation != null) {
            LatLng agentLatLng = new LatLng(agentLocation.latitude(), agentLocation.longitude());
            if (agentMarker == null) {
                agentMarker = googleMap.addMarker(new MarkerOptions()
                        .position(agentLatLng)
                        .title("AGENTE GPS EN VIVO")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            } else {
                agentMarker.setPosition(agentLatLng);
            }

            if (agentToleranceCircle == null) {
                agentToleranceCircle = googleMap.addCircle(new CircleOptions()
                        .center(agentLatLng)
                        .radius(35.0)
                        .strokeWidth(3f)
                        .strokeColor(Color.parseColor("#0284C7"))
                        .fillColor(Color.parseColor("#3338BDF8")));
            } else {
                agentToleranceCircle.setCenter(agentLatLng);
            }
        } else {
            if (agentMarker != null) {
                agentMarker.remove();
                agentMarker = null;
            }
            if (agentToleranceCircle != null) {
                agentToleranceCircle.remove();
                agentToleranceCircle = null;
            }
        }

        if (reframeCamera && shouldAutoFrameCamera) {
            frameCameraToActiveElements(false);
        }
    }

    private void frameCameraToActiveElements(boolean animate) {
        if (googleMap == null) {
            return;
        }

        List<LatLng> pointsToInclude = new ArrayList<>();
        if (activePolylinePoints != null && !activePolylinePoints.isEmpty()) {
            for (GeoPoint pt : activePolylinePoints) {
                pointsToInclude.add(new LatLng(pt.latitude(), pt.longitude()));
            }
        }
        if (stationLocation != null) {
            pointsToInclude.add(new LatLng(stationLocation.latitude(), stationLocation.longitude()));
        }
        if (destinationLocation != null) {
            pointsToInclude.add(new LatLng(destinationLocation.latitude(), destinationLocation.longitude()));
        }
        if (agentLocation != null) {
            pointsToInclude.add(new LatLng(agentLocation.latitude(), agentLocation.longitude()));
        }

        if (pointsToInclude.isEmpty()) {
            LatLng center = userRelativeCenterLocation != null
                    ? new LatLng(userRelativeCenterLocation.latitude(), userRelativeCenterLocation.longitude())
                    : DEFAULT_GUATEMALA_CENTER;
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(center, DEFAULT_ZOOM));
            return;
        }

        if (pointsToInclude.size() == 1) {
            LatLng single = pointsToInclude.get(0);
            if (animate) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(single, 15.6f));
            } else {
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(single, 15.6f));
            }
            return;
        }

        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (LatLng ll : pointsToInclude) {
            builder.include(ll);
        }
        LatLngBounds bounds = builder.build();

        post(() -> {
            if (googleMap == null || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            try {
                int padding = Math.min(getWidth(), getHeight()) / 6;
                if (animate) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding));
                } else {
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding));
                }
            } catch (Throwable ignored) {
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.getCenter(), 14.5f));
            }
        });
    }

    private void refreshHudBannerText() {
        if (hudOverlayBanner == null) {
            return;
        }
        String configuredKey = "";
        try {
            configuredKey = getContext().getString(R.string.google_maps_key);
        } catch (Throwable ignored) {
        }
        boolean hasRealKey = !configuredKey.isBlank()
                && !configuredKey.equals("TU_API_KEY_DE_GOOGLE_MAPS_AQUI");

        String keyNote = hasRealKey
                ? "Google Maps SDK Activo"
                : "Configure su API Key en res/values/strings.xml (google_maps_key)";

        hudOverlayBanner.setText(String.format(
                Locale.US,
                "%s | %s\n%s",
                mapTitle,
                keyNote,
                interactionHint
        ));
    }
}
