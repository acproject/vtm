/*
 * Copyright 2010, 2011, 2012 mapsforge.org
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.mapsforge.android;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import javax.xml.parsers.ParserConfigurationException;

import org.mapsforge.android.mapgenerator.IMapGenerator;
import org.mapsforge.android.mapgenerator.JobQueue;
import org.mapsforge.android.mapgenerator.JobTile;
import org.mapsforge.android.mapgenerator.MapDatabaseFactory;
import org.mapsforge.android.mapgenerator.MapDatabases;
import org.mapsforge.android.mapgenerator.MapRendererFactory;
import org.mapsforge.android.mapgenerator.MapRenderers;
import org.mapsforge.android.mapgenerator.MapWorker;
import org.mapsforge.android.mapgenerator.Theme;
import org.mapsforge.android.rendertheme.ExternalRenderTheme;
import org.mapsforge.android.rendertheme.InternalRenderTheme;
import org.mapsforge.android.rendertheme.RenderTheme;
import org.mapsforge.android.rendertheme.RenderThemeHandler;
import org.mapsforge.android.utils.GlConfigChooser;
import org.mapsforge.core.GeoPoint;
import org.mapsforge.core.MapPosition;
import org.mapsforge.core.Tile;
import org.mapsforge.database.FileOpenResult;
import org.mapsforge.database.IMapDatabase;
import org.mapsforge.database.MapFileInfo;
import org.mapsforge.database.mapfile.MapDatabase;
import org.xml.sax.SAXException;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;

/**
 * A MapView shows a map on the display of the device. It handles all user input and touch gestures to move and zoom the
 * map. This MapView also includes a scale bar and zoom controls. The {@link #getController()} method returns a
 * {@link MapController} to programmatically modify the position and zoom level of the map.
 * <p>
 * This implementation supports offline map rendering as well as downloading map images (tiles) over an Internet
 * connection. The operation mode of a MapView can be set in the constructor and changed at runtime with the
 * {@link #setMapDatabase(MapDatabases)} method. Some MapView parameters depend on the selected operation mode.
 * <p>
 * In offline rendering mode a special database file is required which contains the map data. Map files can be stored in
 * any folder. The current map file is set by calling {@link #setMapFile(String)}. To retrieve the current
 * {@link MapDatabase}, use the {@link #getMapDatabase()} method.
 * <p>
 */
public class MapView extends GLSurfaceView {

	final static String TAG = "MapView";

	/**
	 * Default render theme of the MapView.
	 */
	public static final InternalRenderTheme DEFAULT_RENDER_THEME = InternalRenderTheme.OSMARENDER;

	// private static final float DEFAULT_TEXT_SCALE = 1;
	private static final Byte DEFAULT_START_ZOOM_LEVEL = Byte.valueOf((byte) 16);

	public final static boolean debugFrameTime = false;
	public boolean enableRotation = false;

	private final MapController mMapController;
	private final MapViewPosition mMapViewPosition;

	private final MapZoomControls mMapZoomControls;
	private final Projection mProjection;
	private final TouchHandler mTouchEventHandler;

	private IMapDatabase mMapDatabase;
	private MapDatabases mMapDatabaseType;
	private IMapRenderer mMapRenderer;
	private JobQueue mJobQueue;
	private MapWorker mMapWorkers[];
	private int mNumMapWorkers = 4;
	private DebugSettings debugSettings;
	private String mMapFile;

	/**
	 * @param context
	 *            the enclosing MapActivity instance.
	 * @throws IllegalArgumentException
	 *             if the context object is not an instance of {@link MapActivity} .
	 */
	public MapView(Context context) {
		this(context, null, MapRenderers.GL_RENDERER, MapDatabases.MAP_READER);
	}

	/**
	 * @param context
	 *            the enclosing MapActivity instance.
	 * @param attributeSet
	 *            a set of attributes.
	 * @throws IllegalArgumentException
	 *             if the context object is not an instance of {@link MapActivity} .
	 */
	public MapView(Context context, AttributeSet attributeSet) {
		this(context, attributeSet,
				MapRendererFactory.getMapGenerator(attributeSet),
				MapDatabaseFactory.getMapDatabase(attributeSet));
	}

	private boolean mDebugDatabase = false;

	private MapView(Context context, AttributeSet attributeSet,
			MapRenderers mapGeneratorType, MapDatabases mapDatabaseType) {

		super(context, attributeSet);

		if (!(context instanceof MapActivity)) {
			throw new IllegalArgumentException(
					"context is not an instance of MapActivity");
		}
		Log.d(TAG, "create MapView: " + mapDatabaseType.name());

		// TODO make this dpi dependent
		Tile.TILE_SIZE = 400;

		MapActivity mapActivity = (MapActivity) context;

		debugSettings = new DebugSettings(false, false, false, false);

		mMapController = new MapController(this);

		mMapDatabaseType = mapDatabaseType;

		mMapViewPosition = new MapViewPosition(this);

		mMapZoomControls = new MapZoomControls(mapActivity, this);

		mProjection = new MapViewProjection(this);

		mTouchEventHandler = new TouchHandler(mapActivity, this);

		mJobQueue = new JobQueue();

		mMapRenderer = MapRendererFactory.createMapRenderer(this, mapGeneratorType);
		mMapWorkers = new MapWorker[mNumMapWorkers];

		for (int i = 0; i < mNumMapWorkers; i++) {
			IMapDatabase mapDatabase;
			if (mDebugDatabase) {
				mapDatabase = MapDatabaseFactory
						.createMapDatabase(MapDatabases.TEST_READER);
			} else {
				mapDatabase = MapDatabaseFactory.createMapDatabase(mapDatabaseType);
			}

			IMapGenerator mapGenerator = mMapRenderer.createMapGenerator();
			mapGenerator.setMapDatabase(mapDatabase);

			if (i == 0)
				mMapDatabase = mapDatabase;

			mMapWorkers[i] = new MapWorker(i, this, mapGenerator, mMapRenderer);
			mMapWorkers[i].start();
		}

		setMapFile("default");
		initMapStartPosition();

		mapActivity.registerMapView(this);

		// if (!setRenderTheme(DEFAULT_RENDER_THEME)) {
		// Log.d(TAG, "X could not parse theme");
		// // FIXME show init error dialog
		// }

		setEGLConfigChooser(new GlConfigChooser());
		setEGLContextClientVersion(2);
		// setDebugFlags(DEBUG_CHECK_GL_ERROR | DEBUG_LOG_GL_CALLS);

		setRenderer(mMapRenderer);

		if (!debugFrameTime)
			setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
	}

	private void initMapStartPosition() {
		GeoPoint startPoint = getStartPoint();
		if (startPoint != null) {
			mMapViewPosition.setMapCenter(startPoint);
		}

		Byte startZoomLevel = getStartZoomLevel();
		if (startZoomLevel != null) {
			mMapViewPosition.setZoomLevel(startZoomLevel.byteValue());
		}
	}

	/**
	 * @return the MapController for this MapView.
	 */
	public MapController getController() {
		return mMapController;
	}

	/**
	 * @return the debug settings which are used in this MapView.
	 */
	public DebugSettings getDebugSettings() {
		return debugSettings;
	}

	/**
	 * @return the job queue which is used in this MapView.
	 */
	public JobQueue getJobQueue() {
		return mJobQueue;
	}

	/**
	 * @return the map database which is used for reading map files.
	 */
	public IMapDatabase getMapDatabase() {
		return mMapDatabase;
	}

	/**
	 * @return the currently used map file.
	 */
	public String getMapFile() {
		return mMapFile;
	}

	/**
	 * @return the current position and zoom level of this MapView.
	 */
	public MapViewPosition getMapPosition() {
		return mMapViewPosition;
	}

	/**
	 * @return the currently used projection of the map. Do not keep this object for a longer time.
	 */
	public Projection getProjection() {
		return mProjection;
	}

	@Override
	public boolean onTouchEvent(MotionEvent motionEvent) {
		return mTouchEventHandler.handleMotionEvent(motionEvent);
	}

	/**
	 * Calculates all necessary tiles and adds jobs accordingly.
	 */
	public void redrawTiles() {
		if (getWidth() <= 0 || getHeight() <= 0)
			return;

		mMapRenderer.updateMap(false);
	}

	private void clearAndRedrawMapView() {
		if (getWidth() <= 0 || getHeight() <= 0)
			return;

		mMapRenderer.updateMap(true);
	}

	/**
	 * Sets the visibility of the zoom controls.
	 * 
	 * @param showZoomControls
	 *            true if the zoom controls should be visible, false otherwise.
	 */
	public void setBuiltInZoomControls(boolean showZoomControls) {
		mMapZoomControls.setShowMapZoomControls(showZoomControls);

	}

	/**
	 * Sets the center of the MapView and triggers a redraw.
	 * 
	 * @param geoPoint
	 *            the new center point of the map.
	 */
	public void setCenter(GeoPoint geoPoint) {
		MapPosition mapPosition = new MapPosition(geoPoint,
				mMapViewPosition.getZoomLevel(), 1);
		setCenterAndZoom(mapPosition);
	}

	/**
	 * @param debugSettings
	 *            the new DebugSettings for this MapView.
	 */
	public void setDebugSettings(DebugSettings debugSettings) {
		this.debugSettings = debugSettings;

		clearAndRedrawMapView();
	}

	/**
	 * Sets the map file for this MapView.
	 * 
	 * @param mapFile
	 *            the path to the map file.
	 * @return true if the map file was set correctly, false otherwise.
	 */
	public boolean setMapFile(String mapFile) {
		FileOpenResult fileOpenResult = null;

		Log.i(TAG, "set mapfile " + mapFile);

		if (mapFile != null && mapFile.equals(mMapFile)) {
			// same map file as before
			return false;
		}

		boolean initialized = false;

		mJobQueue.clear();

		mapWorkersPause(true);

		for (MapWorker mapWorker : mMapWorkers) {

			IMapGenerator mapGenerator = mapWorker.getMapGenerator();
			IMapDatabase mapDatabase = mapGenerator.getMapDatabase();

			mapDatabase.closeFile();

			if (mapFile != null)
				fileOpenResult = mapDatabase.openFile(new File(mapFile));
			else
				fileOpenResult = mapDatabase.openFile(null);

			if (fileOpenResult != null && fileOpenResult.isSuccess()) {
				mMapFile = mapFile;

				if (!initialized)
					initialized = true;
			}
		}

		mapWorkersProceed();

		if (initialized) {
			clearAndRedrawMapView();
			Log.i(TAG, "mapfile set");
			return true;
		}

		mMapFile = null;
		Log.i(TAG, "loading mapfile failed");

		return false;
	}

	private GeoPoint getStartPoint() {
		if (mMapDatabase != null && mMapDatabase.hasOpenFile()) {
			MapFileInfo mapFileInfo = mMapDatabase.getMapFileInfo();

			if (mapFileInfo.startPosition != null) {
				return mapFileInfo.startPosition;
			} else if (mapFileInfo.mapCenter != null) {
				return mapFileInfo.mapCenter;
			}
		}

		return null;
	}

	private Byte getStartZoomLevel() {
		if (mMapDatabase != null && mMapDatabase.hasOpenFile()) {
			MapFileInfo mapFileInfo = mMapDatabase.getMapFileInfo();

			if (mapFileInfo.startZoomLevel != null) {
				return mapFileInfo.startZoomLevel;
			}
		}

		return DEFAULT_START_ZOOM_LEVEL;
	}

	/**
	 * Sets the MapDatabase for this MapView.
	 * 
	 * @param mapDatabaseType
	 *            the new MapDatabase.
	 */

	public void setMapDatabase(MapDatabases mapDatabaseType) {
		if (mDebugDatabase)
			return;

		IMapGenerator mapGenerator;

		Log.i(TAG, "setMapDatabase " + mapDatabaseType.name());

		if (mMapDatabaseType == mapDatabaseType)
			return;

		mMapDatabaseType = mapDatabaseType;

		mapWorkersPause(true);

		for (MapWorker mapWorker : mMapWorkers) {
			mapGenerator = mapWorker.getMapGenerator();

			mapGenerator.setMapDatabase(MapDatabaseFactory
					.createMapDatabase(mapDatabaseType));
		}

		mJobQueue.clear();

		String mapFile = mMapFile;
		mMapFile = null;
		setMapFile(mapFile);

		mapWorkersProceed();
	}

	private String mRenderTheme;

	public String getRenderTheme() {
		return mRenderTheme;
	}

	/**
	 * Sets the internal theme which is used for rendering the map.
	 * 
	 * @param internalRenderTheme
	 *            the internal rendering theme.
	 * @return ...
	 * @throws IllegalArgumentException
	 *             if the supplied internalRenderTheme is null.
	 */
	public boolean setRenderTheme(InternalRenderTheme internalRenderTheme) {
		if (internalRenderTheme == null) {
			throw new IllegalArgumentException("render theme must not be null");
		}

		boolean ret = setRenderTheme((Theme) internalRenderTheme);
		if (ret) {
			mRenderTheme = internalRenderTheme.name();
		}

		clearAndRedrawMapView();
		return ret;
	}

	/**
	 * Sets the theme file which is used for rendering the map.
	 * 
	 * @param renderThemePath
	 *            the path to the XML file which defines the rendering theme.
	 * @throws IllegalArgumentException
	 *             if the supplied internalRenderTheme is null.
	 * @throws FileNotFoundException
	 *             if the supplied file does not exist, is a directory or cannot be read.
	 */
	public void setRenderTheme(String renderThemePath) throws FileNotFoundException {
		if (renderThemePath == null) {
			throw new IllegalArgumentException("render theme path must not be null");
		}

		boolean ret = setRenderTheme(new ExternalRenderTheme(renderThemePath));
		if (ret) {
			mRenderTheme = renderThemePath;
		}

		clearAndRedrawMapView();
	}

	private boolean setRenderTheme(Theme theme) {

		mapWorkersPause(true);

		InputStream inputStream = null;
		try {
			inputStream = theme.getRenderThemeAsStream();
			RenderTheme t = RenderThemeHandler.getRenderTheme(inputStream);
			mMapRenderer.setRenderTheme(t);
			mMapWorkers[0].getMapGenerator().setRenderTheme(t);
			return true;
		} catch (ParserConfigurationException e) {
			Log.e(TAG, e.getMessage());
		} catch (SAXException e) {
			Log.e(TAG, e.getMessage());
		} catch (IOException e) {
			Log.e(TAG, e.getMessage());
		} finally {
			try {
				if (inputStream != null) {
					inputStream.close();
				}
			} catch (IOException e) {
				Log.e(TAG, e.getMessage());
			}
			mapWorkersProceed();
		}

		return false;
	}

	/**
	 * Sets the text scale for the map rendering. Has no effect in downloading mode.
	 * 
	 * @param textScale
	 *            the new text scale for the map rendering.
	 */
	// public void setTextScale(float textScale) {
	// mJobParameters = new JobParameters(mJobParameters.theme, textScale);
	// clearAndRedrawMapView();
	// }

	/**
	 * Zooms in or out by the given amount of zoom levels.
	 * 
	 * @param zoomLevelDiff
	 *            the difference to the current zoom level.
	 * @return true if the zoom level was changed, false otherwise.
	 */
	public boolean zoom(byte zoomLevelDiff) {

		int z = mMapViewPosition.getZoomLevel() + zoomLevelDiff;
		if (zoomLevelDiff > 0) {
			// check if zoom in is possible
			if (z > getMaximumPossibleZoomLevel()) {
				return false;
			}

		} else if (zoomLevelDiff < 0) {
			// check if zoom out is possible
			if (z < mMapZoomControls.getZoomLevelMin()) {
				return false;
			}
		}

		mMapViewPosition.setZoomLevel((byte) z);

		redrawTiles();

		return true;
	}

	@Override
	protected synchronized void onSizeChanged(int width, int height, int oldWidth,
			int oldHeight) {

		mJobQueue.clear();

		mapWorkersPause(true);

		super.onSizeChanged(width, height, oldWidth, oldHeight);

		mapWorkersProceed();
	}

	void destroy() {
		for (MapWorker mapWorker : mMapWorkers) {
			mapWorker.pause();
			mapWorker.interrupt();

			try {
				mapWorker.join();
			} catch (InterruptedException e) {
				// restore the interrupted status
				Thread.currentThread().interrupt();
			}
			IMapDatabase mapDatabase = mapWorker.getMapGenerator().getMapDatabase();
			mapDatabase.closeFile();
		}
	}

	/**
	 * @return the maximum possible zoom level.
	 */
	byte getMaximumPossibleZoomLevel() {
		return (byte) 20;
		// FIXME Math.min(mMapZoomControls.getZoomLevelMax(),
		// mMapGenerator.getZoomLevelMax());
	}

	/**
	 * @return true if the current center position of this MapView is valid, false otherwise.
	 */
	boolean hasValidCenter() {
		if (!mMapViewPosition.isValid()) {
			return false;
		} else if (!mMapDatabase.hasOpenFile()
				|| !mMapDatabase.getMapFileInfo().boundingBox.contains(getMapPosition()
						.getMapCenter())) {
			return false;
		}

		return true;
	}

	byte limitZoomLevel(byte zoom) {
		return (byte) Math.max(Math.min(zoom, getMaximumPossibleZoomLevel()),
				mMapZoomControls.getZoomLevelMin());
	}

	@Override
	public void onPause() {
		super.onPause();
		mapWorkersPause(false);
	}

	@Override
	public void onResume() {
		super.onResume();
		mapWorkersProceed();
	}

	/**
	 * Sets the center and zoom level of this MapView and triggers a redraw.
	 * 
	 * @param mapPosition
	 *            the new map position of this MapView.
	 */
	void setCenterAndZoom(MapPosition mapPosition) {

		mMapViewPosition.setMapCenterAndZoomLevel(mapPosition);
		redrawTiles();
	}

	/**
	 * @return MapPosition
	 */
	public MapViewPosition getMapViewPosition() {
		return mMapViewPosition;
	}

	/**
	 * add jobs and remember MapWorkers that stuff needs to be done
	 * 
	 * @param jobs
	 *            tile jobs
	 */
	public void addJobs(ArrayList<JobTile> jobs) {
		if (jobs == null) {
			mJobQueue.clear();
			return;
		}
		mJobQueue.setJobs(jobs);

		for (int i = 0; i < mNumMapWorkers; i++) {
			MapWorker m = mMapWorkers[i];
			synchronized (m) {
				m.notify();
			}
		}
	}

	private void mapWorkersPause(boolean wait) {
		for (MapWorker mapWorker : mMapWorkers) {
			if (!mapWorker.isPausing())
				mapWorker.pause();
		}
		if (wait) {
			for (MapWorker mapWorker : mMapWorkers) {
				if (!mapWorker.isPausing())
					mapWorker.awaitPausing();
			}
		}
	}

	private void mapWorkersProceed() {
		for (MapWorker mapWorker : mMapWorkers)
			mapWorker.proceed();
	}

	public void enableRotation(boolean enable) {
		enableRotation = enable;
	}

	// public final int
	// public Handler messageHandler = new Handler() {
	//
	// @Override
	// public void handleMessage(Message msg) {
	// switch (msg.what) {
	// // handle update
	// // .....
	// }
	// }
	//
	// };

}
