package com.holki.auraleaf.curl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import com.holki.auraleaf.HapticHelper;
import com.holki.auraleaf.SoundHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * RealisticBookEngine for Android.
 * Port of the 2.5D Analytical Projective Geometry & Spring-Damper Page Curl Engine.
 * Features:
 *  - Perpendicular Midpoint Bisector Fold Line
 *  - Sutherland-Hodgman Convex Polygon Half-Plane Clipping
 *  - Householder 2D Reflection Tensor Matrix
 *  - Elastic Quadratic Bezier Camber Edge Flexion
 *  - 2nd-Order Differential Spring-Mass-Damper Inertial Tracking
 *  - Dynamic Spine Tau Clamping (|tau| <= xFoldCenter * 0.96)
 *  - Grab-Anywhere Normalized Pull Mapping
 *  - Tactile Radial Touch Depression Wave
 *  - Luminous Paper Edge Sheen & Multi-Stage Shadows
 */
public class CurlView extends View {

    public interface OnPageListener {
        void onPageChanged(int newPageIndex);
    }

    public interface OnTapListener {
        void onSingleTap();
        void onDoubleTap();
    }

    public interface PageProvider {
        int getPageCount();
        void updatePage(CurlPage page, int width, int height, int index);
    }

    public static final int SHOW_ONE_PAGE = 1;
    public static final int SHOW_TWO_PAGES = 2;

    private OnPageListener mPageListener;
    private OnTapListener mTapListener;
    private SoundHelper mSoundHelper;
    private HapticHelper mHapticHelper;
    private PageProvider mPageProvider;

    private int mViewMode = SHOW_ONE_PAGE;
    private int mCurrentIndex = 0;
    private int mBgColor = Color.TRANSPARENT;

    // Zoom & Pan
    private GestureDetector mGestureDetector;
    private ScaleGestureDetector mScaleDetector;
    private float mZoomScale = 1.0f;
    private float mPanX = 0f;
    private float mPanY = 0f;
    private float mLastPanX = 0f;
    private float mLastPanY = 0f;
    private float mTouchSlop = 32f;

    // Physics & Engine Config
    public static class Config {
        public float softness = 0.85f;     // Radius of cylindrical fold curve
        public float fluidity = 0.78f;     // Spring-damper responsiveness
        public float camber = 0.75f;       // Elastic Bezier edge flexion
        public float shadowDepth = 0.90f;  // Intensity of 3D shadows and highlights
    }
    private final Config mConfig = new Config();

    // Curl Physics State
    private static class CurlState {
        boolean active = false;
        boolean isDragging = false;
        boolean isHovering = false;
        boolean isAnimatingRelease = false;
        int side = 1; // +1 = right page forward, -1 = left page backward

        float grabX = 0f;
        float grabY = 0f;
        float targetX = 0f;
        float targetY = 0f;
        float curX = 0f;
        float curY = 0f;
        float velX = 0f;
        float velY = 0f;

        float pressStrength = 0f;
        boolean completeFlipOnFinish = false;
        int targetIndexAfterFlip = 0;
    }
    private final CurlState mCurl = new CurlState();

    // Dimensions
    private float mBookCenterX = 0f;
    private float mBookCenterY = 0f;
    private float mPageW = 400f;
    private float mPageH = 600f;
    private long mLastPhysicsTime = 0;

    // Drawing Paints & Reusable Paths
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint mGradPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSpinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mHouseholderMatrix = new Matrix();
    private final Matrix mTempMatrix = new Matrix();
    private final Rect mSrcRect = new Rect();
    private final RectF mDstRect = new RectF();

    // Page Texture Cache
    private final CurlPage mCacheCurrent = new CurlPage();
    private final CurlPage mCacheRevealed = new CurlPage();
    private final CurlPage mCacheLeft = new CurlPage();
    private int mCachedCurrentIdx = -1;
    private int mCachedRevealedIdx = -1;
    private int mCachedLeftIdx = -1;

    public CurlView(Context context) {
        super(context);
        init(context);
    }

    public CurlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CurlView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mEdgePaint.setStyle(Paint.Style.STROKE);
        mEdgePaint.setStrokeWidth(3.0f);
        mEdgePaint.setColor(Color.argb(140, 226, 232, 240));

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!mCurl.isDragging && !mCurl.active && mTapListener != null) {
                    mTapListener.onSingleTap();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                if (mTapListener != null) {
                    mTapListener.onDoubleTap();
                }
                return true;
            }
        });

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                mZoomScale = Math.max(1.0f, Math.min(3.5f, mZoomScale * factor));
                if (mZoomScale <= 1.05f) {
                    mZoomScale = 1.0f;
                    mPanX = 0f;
                    mPanY = 0f;
                }
                invalidate();
                return true;
            }
        });
        mScaleDetector.setQuickScaleEnabled(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculateBookDimensions(w, h);
        invalidateCaches();
    }

    private void calculateBookDimensions(int viewW, int viewH) {
        mBookCenterX = viewW / 2f;
        mBookCenterY = viewH / 2f;

        if (mViewMode == SHOW_TWO_PAGES) {
            float maxBookW = viewW * 0.94f;
            float maxBookH = viewH * 0.90f;
            float singleAspect = 0.70f;

            float h = maxBookH;
            float w = h * singleAspect;
            if (w * 2f > maxBookW) {
                w = maxBookW / 2f;
                h = w / singleAspect;
            }
            mPageW = Math.max(100f, w);
            mPageH = Math.max(150f, h);
        } else {
            // Single Page mode: page fills the screen height/width gracefully
            float maxBookW = viewW * 0.96f;
            float maxBookH = viewH * 0.96f;
            float singleAspect = 0.70f;

            float h = maxBookH;
            float w = h * singleAspect;
            if (w > maxBookW) {
                w = maxBookW;
                h = w / singleAspect;
            }
            mPageW = Math.max(100f, w);
            mPageH = Math.max(150f, h);
        }
    }

    private void invalidateCaches() {
        mCachedCurrentIdx = -1;
        mCachedRevealedIdx = -1;
        mCachedLeftIdx = -1;
    }

    public void setPageProvider(PageProvider provider) {
        mPageProvider = provider;
        mCurrentIndex = 0;
        invalidateCaches();
        invalidate();
    }

    public void setCurrentIndex(int index) {
        if (mPageProvider == null) {
            mCurrentIndex = 0;
        } else {
            mCurrentIndex = Math.max(0, Math.min(index, mPageProvider.getPageCount() - 1));
        }
        invalidateCaches();
        invalidate();
    }

    public int getCurrentIndex() {
        return mCurrentIndex;
    }

    public void setViewMode(int mode) {
        mViewMode = mode;
        calculateBookDimensions(getWidth(), getHeight());
        invalidateCaches();
        invalidate();
    }

    public int getViewMode() {
        return mViewMode;
    }

    public void setSoundHelper(SoundHelper helper) {
        mSoundHelper = helper;
    }

    public void setHapticHelper(HapticHelper helper) {
        mHapticHelper = helper;
    }

    public void setOnPageListener(OnPageListener listener) {
        mPageListener = listener;
    }

    public void setOnTapListener(OnTapListener listener) {
        mTapListener = listener;
    }

    public void resetZoom() {
        mZoomScale = 1.0f;
        mPanX = 0f;
        mPanY = 0f;
        invalidate();
    }

    public boolean isZoomed() {
        return mZoomScale > 1.05f;
    }

    public float getZoomScale() {
        return mZoomScale;
    }

    public void setBackgroundColor(int color) {
        mBgColor = color;
        invalidate();
    }

    public void onResume() {}
    public void onPause() {}
    public void setMargins(float left, float top, float right, float bottom) {}
    public void setAllowLastPageCurl(boolean allow) {}
    public void setEnableTouchPressure(boolean enable) {}
    public void reloadPages() {
        invalidateCaches();
        invalidate();
    }

    // Programmatic flip next
    public void flipNext() {
        if (mPageProvider == null || mCurl.isDragging) return;
        int total = mPageProvider.getPageCount();
        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
        if (mCurrentIndex + step >= total && mCurrentIndex >= total - 1) return;

        startProgrammaticFlip(1, mCurrentIndex + step);
    }

    // Programmatic flip prev
    public void flipPrev() {
        if (mPageProvider == null || mCurl.isDragging) return;
        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
        if (mCurrentIndex <= 0) return;

        startProgrammaticFlip(-1, Math.max(0, mCurrentIndex - step));
    }

    private void startProgrammaticFlip(int side, int targetSpread) {
        mCurl.active = true;
        mCurl.isDragging = false;
        mCurl.isHovering = false;
        mCurl.isAnimatingRelease = true;
        mCurl.side = side;

        float W = mPageW;
        float H = mPageH;
        mCurl.grabX = side * W * 0.85f;
        mCurl.grabY = H * 0.28f;
        mCurl.curX = side * W * 0.82f;
        mCurl.curY = H * 0.26f;
        mCurl.velX = -side * W * 2.2f;
        mCurl.velY = -H * 0.35f;
        mCurl.targetX = -side * W;
        mCurl.targetY = 0f;
        mCurl.completeFlipOnFinish = true;
        mCurl.targetIndexAfterFlip = targetSpread;

        if (mSoundHelper != null) mSoundHelper.playFlip();
        if (mHapticHelper != null) mHapticHelper.vibratePageFlip();
        mLastPhysicsTime = System.nanoTime();
        postInvalidateOnAnimation();
    }

    // Local coordinates: spine is at (0, 0)
    private float screenToBookX(float screenX) {
        return screenX - mBookCenterX;
    }

    private float screenToBookY(float screenY) {
        return screenY - mBookCenterY;
    }

    @Override
    public boolean onTouchEvent(MotionEvent me) {
        if (mGestureDetector != null) {
            mGestureDetector.onTouchEvent(me);
        }
        if (mScaleDetector != null) {
            mScaleDetector.onTouchEvent(me);
        }

        int action = me.getActionMasked();
        if (mScaleDetector != null && mScaleDetector.isInProgress()) {
            mCurl.isDragging = false;
            mLastPanX = me.getX();
            mLastPanY = me.getY();
            return true;
        }

        if (me.getPointerCount() > 1 || mZoomScale > 1.05f) {
            // Pan gesture when zoomed
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = me.getX() - mLastPanX;
                float dy = me.getY() - mLastPanY;
                mLastPanX = me.getX();
                mLastPanY = me.getY();
                mPanX += dx;
                mPanY += dy;
                invalidate();
            } else if (action == MotionEvent.ACTION_DOWN) {
                mLastPanX = me.getX();
                mLastPanY = me.getY();
            }
            return true;
        }

        float bx = screenToBookX(me.getX());
        float by = screenToBookY(me.getY());

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                handleGrabStart(bx, by);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mCurl.isDragging) {
                    handleGrabMove(bx, by);
                } else if (!mCurl.active) {
                    float dist = (float) Math.hypot(me.getX() - (mCurl.grabX + mBookCenterX), me.getY() - (mCurl.grabY + mBookCenterY));
                    if (dist > mTouchSlop) {
                        handleGrabStart(bx, by);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mCurl.isDragging) {
                    handleGrabEnd();
                }
                return true;
            }
        }
        return super.onTouchEvent(me);
    }

    private void handleGrabStart(float bx, float by) {
        if (mPageProvider == null) return;
        float W = mPageW;
        float H = mPageH;

        if (Math.abs(bx) > W * 1.15f || Math.abs(by) > H * 0.60f) return;

        // side: +1 = right page, -1 = left page
        int side = (mViewMode == SHOW_TWO_PAGES) ? (bx >= 0 ? 1 : -1) : (bx >= 0 ? 1 : -1);

        int total = mPageProvider.getPageCount();
        if (side == 1 && mCurrentIndex >= total - 1) return;
        if (side == -1 && mCurrentIndex <= 0) return;

        mCurl.active = true;
        mCurl.isDragging = true;
        mCurl.isHovering = false;
        mCurl.isAnimatingRelease = false;
        mCurl.side = side;

        mCurl.grabX = Math.max(-W, Math.min(W, bx));
        mCurl.grabY = Math.max(-H / 2f, Math.min(H / 2f, by));
        mCurl.pressStrength = 1.0f;

        float initialLift = W * (0.14f + 0.08f * mConfig.softness);
        float startEdgeX = side == 1 ? W - initialLift : -W + initialLift;
        float startEdgeY = mCurl.grabY * 0.92f;

        mCurl.curX = side == 1 ? W : -W;
        mCurl.curY = mCurl.grabY;
        mCurl.targetX = startEdgeX;
        mCurl.targetY = startEdgeY;
        mCurl.velX = 0f;
        mCurl.velY = 0f;

        if (mHapticHelper != null) mHapticHelper.vibratePageTouch();
        mLastPhysicsTime = System.nanoTime();
        postInvalidateOnAnimation();
    }

    private void handleGrabMove(float bx, float by) {
        float W = mPageW;
        float H = mPageH;
        int side = mCurl.side;

        float mappedX;
        if (side == 1) {
            float span = Math.max(W * 0.65f, mCurl.grabX + W);
            float progress = (mCurl.grabX - bx) / span;
            float baseLift = W * 0.12f;
            mappedX = W - baseLift - progress * (2f * W - baseLift);
        } else {
            float span = Math.max(W * 0.65f, W - mCurl.grabX);
            float progress = (bx - mCurl.grabX) / span;
            float baseLift = W * 0.12f;
            mappedX = -W + baseLift + progress * (2f * W - baseLift);
        }

        mCurl.targetX = Math.max(-W * 1.05f, Math.min(W * 1.05f, mappedX));
        float dy = by - mCurl.grabY;
        mCurl.targetY = Math.max(-H * 0.85f, Math.min(H * 0.85f, mCurl.grabY + dy * 1.15f));
        postInvalidateOnAnimation();
    }

    private void handleGrabEnd() {
        mCurl.isDragging = false;
        mCurl.isHovering = false;
        mCurl.isAnimatingRelease = true;
        mCurl.pressStrength = 0f;

        float W = mPageW;
        int side = mCurl.side;

        float projectedX = mCurl.curX + mCurl.velX * 0.18f;
        boolean shouldComplete = (side == 1) ? (projectedX < W * 0.18f) : (projectedX > -W * 0.18f);

        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
        if (shouldComplete) {
            mCurl.completeFlipOnFinish = true;
            mCurl.targetIndexAfterFlip = (side == 1) ? mCurrentIndex + step : mCurrentIndex - step;
            mCurl.targetX = -side * W;
            mCurl.targetY = 0f;
            if (mSoundHelper != null) mSoundHelper.playFlip();
            if (mHapticHelper != null) mHapticHelper.vibratePageFlip();
        } else {
            mCurl.completeFlipOnFinish = false;
            mCurl.targetX = side * W;
            mCurl.targetY = mCurl.grabY * 0.5f;
        }
        postInvalidateOnAnimation();
    }

    // 2nd-Order Spring-Damper Physics Step
    private void stepPhysics(float dt) {
        if (!mCurl.active) return;

        float stiffness = 110f + (1f - mConfig.fluidity) * 180f;
        float damping = 14f + (1f - mConfig.fluidity) * 12f;

        float ax = (mCurl.targetX - mCurl.curX) * stiffness - mCurl.velX * damping;
        float ay = (mCurl.targetY - mCurl.curY) * stiffness - mCurl.velY * damping;

        mCurl.velX += ax * dt;
        mCurl.velY += ay * dt;
        mCurl.curX += mCurl.velX * dt;
        mCurl.curY += mCurl.velY * dt;

        float speed = (float) Math.hypot(mCurl.velX, mCurl.velY);

        if (mCurl.isAnimatingRelease) {
            float dist = (float) Math.hypot(mCurl.targetX - mCurl.curX, mCurl.targetY - mCurl.curY);
            if (dist < 2.0f && speed < 18f) {
                if (mCurl.completeFlipOnFinish) {
                    mCurrentIndex = mCurl.targetIndexAfterFlip;
                    invalidateCaches();
                    if (mPageListener != null) {
                        final int idx = mCurrentIndex;
                        post(() -> mPageListener.onPageChanged(idx));
                    }
                }
                mCurl.active = false;
                mCurl.isAnimatingRelease = false;
                mCurl.velX = 0f;
                mCurl.velY = 0f;
            }
        }
    }

    // Geometric Point
    public static class PointD {
        public double x, y;
        public PointD(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    // Fold Geometry
    public static class FoldGeometry {
        public double fx, fy;
        public double nx, ny;
        public double xFoldCenter;
        public double liftDist;
        public double curlRadius;
        public double archFactor;
        public double tau;
    }

    private FoldGeometry computeFoldGeometry() {
        FoldGeometry g = new FoldGeometry();
        double W = mPageW;
        double H = mPageH;
        int side = mCurl.side;

        double px = Math.max(-W, Math.min(W - 1, side * mCurl.curX));
        double py = mCurl.curY;
        double gy = mCurl.grabY;

        double xFoldCenter = Math.max(0.001, Math.min(W - 0.5, (W + px) * 0.5));
        double liftDist = W - xFoldCenter;

        double verticalPull = (py - gy) * 0.85 + gy * (liftDist / W < 0.45 ? 0.95 : 0.45);
        double desiredTau = -verticalPull * (0.65 + 0.35 * mConfig.softness);

        double maxSpineTau = xFoldCenter * 0.96;
        double tau = Math.max(-maxSpineTau, Math.min(maxSpineTau, desiredTau));

        double len = Math.hypot(H * 0.5, tau);
        double nxRight = (H * 0.5) / len;
        double nyRight = tau / len;

        g.fx = side * xFoldCenter;
        g.fy = 0;
        g.nx = side * nxRight;
        g.ny = nyRight;
        g.xFoldCenter = xFoldCenter;
        g.liftDist = liftDist;
        g.tau = tau;

        g.archFactor = Math.sin(Math.min(1.0, liftDist / W) * Math.PI);
        g.curlRadius = (22.0 + 65.0 * mConfig.softness) * (0.25 + 0.75 * g.archFactor);
        return g;
    }

    // Sutherland-Hodgman Polygon Half-Plane Clipping
    private List<PointD> clipPolygonHalfPlane(List<PointD> poly, double fx, double fy, double nx, double ny, double keepSign) {
        List<PointD> out = new ArrayList<>();
        int n = poly.size();
        if (n == 0) return out;

        for (int i = 0; i < n; i++) {
            PointD cur = poly.get(i);
            PointD prev = poly.get((i + n - 1) % n);
            double dCur = ((cur.x - fx) * nx + (cur.y - fy) * ny) * keepSign;
            double dPrev = ((prev.x - fx) * nx + (prev.y - fy) * ny) * keepSign;

            if (dCur >= 0) {
                if (dPrev < 0) {
                    double t = dPrev / (dPrev - dCur);
                    out.add(new PointD(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y)));
                }
                out.add(cur);
            } else if (dPrev >= 0) {
                double t = dPrev / (dPrev - dCur);
                out.add(new PointD(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y)));
            }
        }
        return out;
    }

    // Householder Reflection with Camber Flexion
    private PointD reflectPointAcrossFold(PointD pt, double fx, double fy, double nx, double ny, double curlRadius, double camberStrength) {
        double vx = pt.x - fx;
        double vy = pt.y - fy;
        double d = vx * nx + vy * ny;

        double rollCompress = curlRadius * 0.18 * Math.tanh(d / Math.max(8.0, curlRadius));
        double effectiveD = d - rollCompress;

        double rx = pt.x - (d + effectiveD) * nx;
        double ry = pt.y - (d + effectiveD) * ny;

        double speedBend = Math.max(-28.0, Math.min(28.0, -mCurl.velY * 0.015)) * camberStrength;
        double edgeRatio = Math.min(1.0, d / mPageW);
        ry += speedBend * edgeRatio * edgeRatio;

        return new PointD(rx, ry);
    }

    // Path tracing with Bezier Camber
    private void traceSoftCurvedPolygon(Path path, List<PointD> poly, double fx, double fy, double nx, double ny, double camberAmount) {
        path.reset();
        if (poly.size() < 3) return;

        path.moveTo((float) poly.get(0).x, (float) poly.get(0).y);
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            PointD p1 = poly.get(i);
            PointD p2 = poly.get((i + 1) % n);

            double d1 = Math.abs((p1.x - fx) * nx + (p1.y - fy) * ny);
            double d2 = Math.abs((p2.x - fx) * nx + (p2.y - fy) * ny);

            if (d1 < 1.5 && d2 < 1.5) {
                path.lineTo((float) p2.x, (float) p2.y);
            } else {
                double mx = (p1.x + p2.x) * 0.5;
                double my = (p1.y + p2.y) * 0.5;
                double edgeLen = Math.hypot(p2.x - p1.x, p2.y - p1.y);
                double curveOffset = Math.min(18.0, edgeLen * 0.055) * camberAmount;
                double cpx = mx - nx * curveOffset;
                double cpy = my - ny * curveOffset * 1.3;
                path.quadTo((float) cpx, (float) cpy, (float) p2.x, (float) p2.y);
            }
        }
        path.close();
    }

    // Fetch bitmap helper
    private Bitmap getPageBitmap(int index, int side) {
        if (mPageProvider == null || index < 0 || index >= mPageProvider.getPageCount()) return null;

        int reqW = (int) mPageW;
        int reqH = (int) mPageH;

        if (index == mCachedCurrentIdx && mCacheCurrent.getRawTexture(side) != null) {
            return mCacheCurrent.getRawTexture(side);
        }
        if (index == mCachedRevealedIdx && mCacheRevealed.getRawTexture(side) != null) {
            return mCacheRevealed.getRawTexture(side);
        }
        if (index == mCachedLeftIdx && mCacheLeft.getRawTexture(side) != null) {
            return mCacheLeft.getRawTexture(side);
        }

        CurlPage pageToFill;
        if (index == mCurrentIndex) {
            pageToFill = mCacheCurrent;
            mCachedCurrentIdx = index;
        } else if (index == mCurrentIndex + 1 || index == mCurrentIndex + 2) {
            pageToFill = mCacheRevealed;
            mCachedRevealedIdx = index;
        } else {
            pageToFill = mCacheLeft;
            mCachedLeftIdx = index;
        }

        mPageProvider.updatePage(pageToFill, reqW, reqH, index);
        return pageToFill.getRawTexture(side);
    }

    private void drawPageBitmap(Canvas canvas, Bitmap bmp, RectF dstRect) {
        if (bmp == null || bmp.isRecycled()) {
            mBitmapPaint.setColor(Color.WHITE);
            canvas.drawRect(dstRect, mBitmapPaint);
            return;
        }
        mSrcRect.set(0, 0, bmp.getWidth(), bmp.getHeight());
        canvas.drawBitmap(bmp, mSrcRect, dstRect, mBitmapPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mBgColor != Color.TRANSPARENT) {
            canvas.drawColor(mBgColor);
        }

        long now = System.nanoTime();
        if (mLastPhysicsTime > 0) {
            float dt = Math.min(0.035f, (now - mLastPhysicsTime) / 1_000_000_000f);
            stepPhysics(dt);
        }
        mLastPhysicsTime = now;

        canvas.save();
        if (mZoomScale > 1.05f) {
            canvas.scale(mZoomScale, mZoomScale, mBookCenterX + mPanX, mBookCenterY + mPanY);
        }
        canvas.translate(mBookCenterX, mBookCenterY);

        float W = mPageW;
        float H = mPageH;

        List<PointD> leftRectPoly = new ArrayList<>();
        leftRectPoly.add(new PointD(-W, -H / 2f));
        leftRectPoly.add(new PointD(0, -H / 2f));
        leftRectPoly.add(new PointD(0, H / 2f));
        leftRectPoly.add(new PointD(-W, H / 2f));

        List<PointD> rightRectPoly = new ArrayList<>();
        rightRectPoly.add(new PointD(0, -H / 2f));
        rightRectPoly.add(new PointD(W, -H / 2f));
        rightRectPoly.add(new PointD(W, H / 2f));
        rightRectPoly.add(new PointD(0, H / 2f));

        // When curl is inactive: draw static open pages
        if (!mCurl.active) {
            if (mViewMode == SHOW_TWO_PAGES) {
                int leftIdx = (mCurrentIndex % 2 == 1) ? mCurrentIndex : mCurrentIndex - 1;
                int rightIdx = leftIdx + 1;
                Bitmap leftBmp = getPageBitmap(leftIdx, CurlPage.SIDE_FRONT);
                Bitmap rightBmp = getPageBitmap(rightIdx, CurlPage.SIDE_FRONT);
                if (leftBmp != null) drawPageBitmap(canvas, leftBmp, new RectF(-W, -H / 2f, 0, H / 2f));
                if (rightBmp != null) drawPageBitmap(canvas, rightBmp, new RectF(0, -H / 2f, W, H / 2f));
                drawCenterSpineCrease(canvas, H);
            } else {
                Bitmap curBmp = getPageBitmap(mCurrentIndex, CurlPage.SIDE_FRONT);
                drawPageBitmap(canvas, curBmp, new RectF(0, -H / 2f, W, H / 2f));
            }
            canvas.restore();
            return;
        }

        // Active Curl Rendering (RealisticBookEngine 2.5D math)
        int side = mCurl.side;
        FoldGeometry geom = computeFoldGeometry();
        double fx = geom.fx, fy = geom.fy, nx = geom.nx, ny = geom.ny;
        double curlRadius = geom.curlRadius, archFactor = geom.archFactor;

        // 1. Draw opposite static page (if in two-page spread)
        if (mViewMode == SHOW_TWO_PAGES) {
            if (side == 1) {
                int leftIdx = (mCurrentIndex % 2 == 1) ? mCurrentIndex : mCurrentIndex - 1;
                Bitmap leftBmp = getPageBitmap(leftIdx, CurlPage.SIDE_FRONT);
                if (leftBmp != null) drawPageBitmap(canvas, leftBmp, new RectF(-W, -H / 2f, 0, H / 2f));
            } else {
                int rightIdx = (mCurrentIndex % 2 == 1) ? mCurrentIndex + 1 : mCurrentIndex;
                Bitmap rightBmp = getPageBitmap(rightIdx, CurlPage.SIDE_FRONT);
                if (rightBmp != null) drawPageBitmap(canvas, rightBmp, new RectF(0, -H / 2f, W, H / 2f));
            }
        }

        // 2. Revealed page underneath (half-plane d >= 0)
        List<PointD> activeSidePoly = (side == 1) ? rightRectPoly : (mViewMode == SHOW_TWO_PAGES ? leftRectPoly : rightRectPoly);
        List<PointD> revealedPoly = clipPolygonHalfPlane(activeSidePoly, fx, fy, nx, ny, 1.0);
        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
        int nextIdx = (side == 1) ? mCurrentIndex + step : mCurrentIndex - step;
        Bitmap revealedBmp = getPageBitmap(nextIdx, CurlPage.SIDE_FRONT);

        if (revealedPoly.size() >= 3 && revealedBmp != null) {
            Path revealedPath = new Path();
            for (int i = 0; i < revealedPoly.size(); i++) {
                PointD p = revealedPoly.get(i);
                if (i == 0) revealedPath.moveTo((float) p.x, (float) p.y);
                else revealedPath.lineTo((float) p.x, (float) p.y);
            }
            revealedPath.close();

            canvas.save();
            canvas.clipPath(revealedPath);
            drawPageBitmap(canvas, revealedBmp, (side == 1) ? new RectF(0, -H / 2f, W, H / 2f) : (mViewMode == SHOW_TWO_PAGES ? new RectF(-W, -H / 2f, 0, H / 2f) : new RectF(0, -H / 2f, W, H / 2f)));
            canvas.restore();
        }

        // 3. Uncurled flat part of current page (half-plane d <= 0)
        List<PointD> uncurledPoly = clipPolygonHalfPlane(activeSidePoly, fx, fy, nx, ny, -1.0);
        Bitmap activeFrontBmp = getPageBitmap(mCurrentIndex, CurlPage.SIDE_FRONT);

        if (uncurledPoly.size() >= 3 && activeFrontBmp != null) {
            Path uncurledPath = new Path();
            for (int i = 0; i < uncurledPoly.size(); i++) {
                PointD p = uncurledPoly.get(i);
                if (i == 0) uncurledPath.moveTo((float) p.x, (float) p.y);
                else uncurledPath.lineTo((float) p.x, (float) p.y);
            }
            uncurledPath.close();

            canvas.save();
            canvas.clipPath(uncurledPath);
            drawPageBitmap(canvas, activeFrontBmp, (side == 1) ? new RectF(0, -H / 2f, W, H / 2f) : (mViewMode == SHOW_TWO_PAGES ? new RectF(-W, -H / 2f, 0, H / 2f) : new RectF(0, -H / 2f, W, H / 2f)));

            // Pre-fold gradient along fold axis
            float x0 = (float) (fx - nx * curlRadius * 0.9);
            float y0 = (float) (fy - ny * curlRadius * 0.9);
            float sh = mConfig.shadowDepth;
            LinearGradient preFoldGrad = new LinearGradient(
                    x0, y0, (float) fx, (float) fy,
                    new int[]{
                            Color.TRANSPARENT,
                            Color.argb((int)(56 * sh * archFactor), 0, 0, 0),
                            Color.argb((int)(35 * sh * archFactor), 255, 255, 255),
                            Color.argb((int)(45 * sh), 0, 0, 0)
                    },
                    new float[]{ 0f, 0.55f, 0.88f, 1.0f },
                    Shader.TileMode.CLAMP
            );
            mGradPaint.setShader(preFoldGrad);
            canvas.drawRect(new RectF(-W, -H / 2f, W, H / 2f), mGradPaint);

            // Tactile touch point depression wave
            if (mCurl.pressStrength > 0.05f) {
                RadialGradient touchGrad = new RadialGradient(
                        mCurl.grabX, mCurl.grabY, (float)(75 * mConfig.softness),
                        new int[]{
                                Color.argb((int)(56 * mCurl.pressStrength), 0, 240, 255),
                                Color.argb((int)(66 * mCurl.pressStrength), 0, 0, 0),
                                Color.TRANSPARENT
                        },
                        new float[]{ 0f, 0.5f, 1f },
                        Shader.TileMode.CLAMP
                );
                mGradPaint.setShader(touchGrad);
                canvas.drawRect(new RectF(-W, -H / 2f, W, H / 2f), mGradPaint);
            }
            canvas.restore();
        }

        // 4. Center Spine Crease
        drawCenterSpineCrease(canvas, H);

        // 5. Cast Soft Drop Shadow & Curled Flap with Householder Reflection
        if (revealedPoly.size() >= 3) {
            List<PointD> reflectedFlapPoly = new ArrayList<>();
            for (PointD pt : revealedPoly) {
                reflectedFlapPoly.add(reflectPointAcrossFold(pt, fx, fy, nx, ny, curlRadius, mConfig.camber));
            }

            // a) Cast Drop Shadow
            double shadowSpread = (18.0 + 55.0 * archFactor) * mConfig.shadowDepth;
            Path shadowPath = new Path();
            traceSoftCurvedPolygon(shadowPath, reflectedFlapPoly, fx, fy, nx, ny, mConfig.camber);

            canvas.save();
            canvas.clipRect(new RectF(-W, -H / 2f, W, H / 2f));
            canvas.save();
            canvas.translate((float)(-nx * shadowSpread * 0.45), (float)(8.0 * archFactor));
            LinearGradient castGrad = new LinearGradient(
                    (float) fx, (float) fy,
                    (float)(fx - nx * (curlRadius * 2.2 + shadowSpread)),
                    (float)(fy - ny * (curlRadius * 2.2 + shadowSpread)),
                    new int[]{
                            Color.argb((int)(165 * mConfig.shadowDepth), 2, 6, 23),
                            Color.argb((int)(96 * mConfig.shadowDepth * archFactor), 2, 6, 23),
                            Color.TRANSPARENT
                    },
                    new float[]{ 0f, 0.5f, 1f },
                    Shader.TileMode.CLAMP
            );
            mGradPaint.setShader(castGrad);
            canvas.drawPath(shadowPath, mGradPaint);
            canvas.restore();
            canvas.restore();

            // b) Back of the curled flap with Householder reflection
            Path flapPath = new Path();
            traceSoftCurvedPolygon(flapPath, reflectedFlapPoly, fx, fy, nx, ny, mConfig.camber);

            Bitmap backBmp = getPageBitmap(mCurrentIndex, CurlPage.SIDE_BACK);
            if (backBmp == null) backBmp = activeFrontBmp;

            canvas.save();
            canvas.clipPath(flapPath);

            // Householder 2D reflection tensor transformation
            canvas.save();
            float[] vals = new float[]{
                    (float)(1.0 - 2.0 * nx * nx), (float)(-2.0 * nx * ny), 0f,
                    (float)(-2.0 * nx * ny), (float)(1.0 - 2.0 * ny * ny), 0f,
                    0f, 0f, 1f
            };
            mHouseholderMatrix.setValues(vals);
            mTempMatrix.reset();
            mTempMatrix.postTranslate((float) -fx, (float) -fy);
            mTempMatrix.postConcat(mHouseholderMatrix);
            mTempMatrix.postTranslate((float) fx, (float) fy);
            canvas.concat(mTempMatrix);

            // Draw reflected back page bitmap
            canvas.save();
            if (side == 1) {
                canvas.translate(W, -H / 2f);
                canvas.scale(-1f, 1f);
                drawPageBitmap(canvas, backBmp, new RectF(0, 0, W, H));
            } else {
                canvas.translate(0, -H / 2f);
                canvas.scale(-1f, 1f);
                drawPageBitmap(canvas, backBmp, new RectF(0, 0, W, H));
            }
            canvas.restore();
            canvas.restore(); // restore Householder

            // Cylindrical 3D specular highlight and gradient across curled sheet
            float cylSpan = (float) Math.max(45.0, curlRadius * 2.5);
            LinearGradient cylGrad = new LinearGradient(
                    (float) fx, (float) fy,
                    (float)(fx - nx * cylSpan), (float)(fy - ny * cylSpan),
                    new int[]{
                            Color.argb((int)(80 * mConfig.shadowDepth), 255, 255, 255),
                            Color.argb((int)(60 * mConfig.shadowDepth * archFactor), 255, 255, 255),
                            Color.TRANSPARENT,
                            Color.argb((int)(70 * mConfig.shadowDepth * archFactor), 2, 6, 23),
                            Color.argb((int)(30 * mConfig.shadowDepth), 2, 6, 23)
                    },
                    new float[]{ 0f, 0.14f, 0.42f, 0.76f, 1.0f },
                    Shader.TileMode.CLAMP
            );
            mGradPaint.setShader(cylGrad);
            canvas.drawRect(new RectF(-W * 1.5f, -H, W * 1.5f, H * 2f), mGradPaint);

            // Luminous cut paper edge highlight
            canvas.drawPath(flapPath, mEdgePaint);
            canvas.restore();
        }

        canvas.restore(); // restore bookCenter

        // Continue 60-120fps physics loop while active
        if (mCurl.active) {
            postInvalidateOnAnimation();
        }
    }

    private void drawCenterSpineCrease(Canvas canvas, float H) {
        if (mViewMode != SHOW_TWO_PAGES) return;
        LinearGradient spineGrad = new LinearGradient(
                -12f, 0f, 12f, 0f,
                new int[]{
                        Color.TRANSPARENT,
                        Color.argb(85, 2, 6, 23),
                        Color.argb(130, 2, 6, 23),
                        Color.argb(35, 255, 255, 255),
                        Color.TRANSPARENT
                },
                new float[]{ 0f, 0.45f, 0.50f, 0.55f, 1.0f },
                Shader.TileMode.CLAMP
        );
        mSpinePaint.setShader(spineGrad);
        canvas.drawRect(new RectF(-12f, -H / 2f, 12f, H / 2f), mSpinePaint);
    }
}
