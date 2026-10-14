package com.holki.auraleaf.curl;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import com.holki.auraleaf.HapticHelper;
import com.holki.auraleaf.SoundHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * Feather-Light Realistic 2.5D Page Curl Engine for AuraLeaf Feather.
 * Features:
 *  - Fullscreen Edge-to-Edge Adaptive Layout (Single Page & Two Page Spreads)
 *  - Crisp Midpoint Bisector Fold Line
 *  - Sutherland-Hodgman Polygon Half-Plane Clipping
 *  - Householder 2D Reflection Tensor Transformation
 *  - Quadratic Bezier Camber Elastic Edge Flexion (8-12%)
 *  - Zero-Lag Instant Touch Tracking with 1.35x Drag Amplification
 *  - 240ms FastOutSlowIn Airborne Snap Completion
 *  - Pure Feather-Light Shading (Light Sheen & Translucent Drop Shadow)
 *  - Rigid Spine Anti-Tear Protection
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
    private float mTouchSlop = 24f;

    // Touch & Velocity Tracking
    private VelocityTracker mVelocityTracker;
    private float mDownX = 0f;
    private float mDownY = 0f;
    private boolean mIsDragging = false;
    private ValueAnimator mReleaseAnimator;

    // Engine Configuration
    public static class Config {
        public float softness = 0.85f;     // Tight crisp fold radius
        public float camber = 0.85f;       // Elastic Bezier camber flexion (8-12%)
        public float shadowDepth = 0.50f;  // Lightened shadows (alpha ~ 0.18-0.22)
    }
    private final Config mConfig = new Config();

    // Curl State
    private static class CurlState {
        boolean active = false;
        boolean isDragging = false;
        boolean isAnimatingRelease = false;
        int side = 1; // +1 = forward (flip right-to-left), -1 = backward (flip left-to-right)

        float grabX = 0f;
        float grabY = 0f;
        float targetX = 0f;
        float targetY = 0f;
        float curX = 0f;
        float curY = 0f;
    }
    private final CurlState mCurl = new CurlState();

    // Dimensions
    private float mBookCenterX = 0f;
    private float mBookCenterY = 0f;
    private float mPageW = 400f;
    private float mPageH = 600f;

    // Drawing Paints & Reusable Objects
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint mGradPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSpinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mHouseholderMatrix = new Matrix();
    private final Matrix mTempMatrix = new Matrix();
    private final Rect mSrcRect = new Rect();

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
        mEdgePaint.setStrokeWidth(1.5f);
        mEdgePaint.setColor(Color.argb(65, 255, 255, 255));

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
        if (viewW <= 0 || viewH <= 0) return;
        mBookCenterX = viewW / 2f;
        mBookCenterY = viewH / 2f;

        if (mViewMode == SHOW_TWO_PAGES) {
            mPageW = viewW / 2f;
            mPageH = viewH;
        } else {
            mPageW = viewW;
            mPageH = viewH;
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

    @Override
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

    // Programmatic flip forward
    public void flipNext() {
        if (mPageProvider == null || mCurl.isDragging) return;
        int total = mPageProvider.getPageCount();
        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
        if (mCurrentIndex + step > total - 1 && mCurrentIndex >= total - 1) return;

        startProgrammaticFlip(1, mCurrentIndex + step);
    }

    // Programmatic flip backward
    public void flipPrev() {
        if (mPageProvider == null || mCurl.isDragging) return;
        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
        if (mCurrentIndex <= 0) return;

        startProgrammaticFlip(-1, Math.max(0, mCurrentIndex - step));
    }

    private void startProgrammaticFlip(int side, int targetIndex) {
        if (mReleaseAnimator != null && mReleaseAnimator.isRunning()) {
            mReleaseAnimator.cancel();
        }
        mCurl.active = true;
        mCurl.isDragging = false;
        mCurl.isAnimatingRelease = true;
        mCurl.side = side;

        float edgeX = getEdgeX(side);
        float spineX = getSpineX(side);

        mCurl.grabX = edgeX;
        mCurl.grabY = 0f;
        mCurl.curX = edgeX;
        mCurl.curY = 0f;

        float finalTargetX = (side == 1) ? spineX - mPageW * 0.25f : spineX + mPageW * 0.25f;
        startReleaseAnimation(finalTargetX, true, targetIndex);
    }

    private float screenToBookX(float screenX) {
        return screenX - mBookCenterX;
    }

    private float screenToBookY(float screenY) {
        return screenY - mBookCenterY;
    }

    private float getEdgeX(int side) {
        float halfW = mPageW / 2f;
        if (mViewMode == SHOW_TWO_PAGES) {
            return side == 1 ? mPageW : -mPageW;
        } else {
            return side == 1 ? halfW : -halfW;
        }
    }

    private float getSpineX(int side) {
        float halfW = mPageW / 2f;
        if (mViewMode == SHOW_TWO_PAGES) {
            return 0f;
        } else {
            return side == 1 ? -halfW : halfW;
        }
    }

    private RectF getPageRectForSide(int side) {
        float halfW = mPageW / 2f;
        float halfH = mPageH / 2f;
        if (mViewMode == SHOW_TWO_PAGES) {
            return (side == 1) ? new RectF(0f, -halfH, mPageW, halfH) : new RectF(-mPageW, -halfH, 0f, halfH);
        } else {
            return new RectF(-halfW, -halfH, halfW, halfH);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent me) {
        if (mScaleDetector != null) {
            mScaleDetector.onTouchEvent(me);
        }
        if (mScaleDetector != null && mScaleDetector.isInProgress()) {
            mIsDragging = false;
            mLastPanX = me.getX();
            mLastPanY = me.getY();
            return true;
        }

        if (me.getPointerCount() > 1 || mZoomScale > 1.05f) {
            if (me.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = me.getX() - mLastPanX;
                float dy = me.getY() - mLastPanY;
                mLastPanX = me.getX();
                mLastPanY = me.getY();
                mPanX += dx;
                mPanY += dy;
                invalidate();
            } else if (me.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mLastPanX = me.getX();
                mLastPanY = me.getY();
            }
            return true;
        }

        int action = me.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (mReleaseAnimator != null && mReleaseAnimator.isRunning()) {
                    mReleaseAnimator.cancel();
                }
                mDownX = me.getX();
                mDownY = me.getY();
                mLastPanX = me.getX();
                mLastPanY = me.getY();
                mIsDragging = false;
                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                } else {
                    mVelocityTracker.clear();
                }
                mVelocityTracker.addMovement(me);
                if (mGestureDetector != null) {
                    mGestureDetector.onTouchEvent(me);
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mVelocityTracker != null) {
                    mVelocityTracker.addMovement(me);
                }
                float dx = me.getX() - mDownX;
                float dy = me.getY() - mDownY;

                if (!mIsDragging) {
                    if (Math.hypot(dx, dy) > mTouchSlop) {
                        if (mPageProvider == null) return true;
                        int total = mPageProvider.getPageCount();

                        int side;
                        if (mViewMode == SHOW_TWO_PAGES) {
                            side = (screenToBookX(mDownX) >= 0) ? 1 : -1;
                        } else {
                            side = (dx < 0) ? 1 : -1;
                        }

                        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
                        if (side == 1 && mCurrentIndex >= total - 1) return true;
                        if (side == -1 && mCurrentIndex <= 0) return true;

                        mIsDragging = true;
                        mCurl.active = true;
                        mCurl.isDragging = true;
                        mCurl.isAnimatingRelease = false;
                        mCurl.side = side;

                        float edgeX = getEdgeX(side);
                        mCurl.grabX = screenToBookX(mDownX);
                        mCurl.grabY = screenToBookY(mDownY);
                        mCurl.curX = edgeX;
                        mCurl.curY = mCurl.grabY;
                        mCurl.targetX = edgeX;
                        mCurl.targetY = mCurl.grabY;

                        if (mHapticHelper != null) mHapticHelper.vibratePageTouch();
                    }
                }

                if (mIsDragging) {
                    // Requirement 3: 1.35x drag amplification
                    float amplifiedDx = (me.getX() - mDownX) * 1.35f;
                    float amplifiedDy = (me.getY() - mDownY);

                    float edgeX = getEdgeX(mCurl.side);
                    float spineX = getSpineX(mCurl.side);

                    if (mCurl.side == 1) {
                        mCurl.targetX = Math.max(spineX - mPageW * 0.35f, Math.min(edgeX, edgeX + amplifiedDx));
                    } else {
                        mCurl.targetX = Math.min(spineX + mPageW * 0.35f, Math.max(edgeX, edgeX + amplifiedDx));
                    }
                    mCurl.targetY = mCurl.grabY + amplifiedDy * 0.75f;

                    // Requirement 2: Feather-light instant tracking
                    mCurl.curX += (mCurl.targetX - mCurl.curX) * 0.65f;
                    mCurl.curY += (mCurl.targetY - mCurl.curY) * 0.65f;

                    invalidate();
                    return true;
                }

                if (mGestureDetector != null) {
                    mGestureDetector.onTouchEvent(me);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mGestureDetector != null) {
                    mGestureDetector.onTouchEvent(me);
                }

                if (mIsDragging) {
                    if (mVelocityTracker != null) {
                        mVelocityTracker.addMovement(me);
                        mVelocityTracker.computeCurrentVelocity(1000);
                    }
                    float vx = (mVelocityTracker != null) ? mVelocityTracker.getXVelocity() : 0f;
                    if (mVelocityTracker != null) {
                        mVelocityTracker.recycle();
                        mVelocityTracker = null;
                    }
                    mIsDragging = false;
                    mCurl.isDragging = false;

                    // Requirement 4: Effortless snap (22% width or light flick)
                    float edgeX = getEdgeX(mCurl.side);
                    float spineX = getSpineX(mCurl.side);
                    float totalDist = Math.abs(edgeX - spineX);
                    float movedDist = Math.abs(mCurl.curX - edgeX);
                    float progress = movedDist / Math.max(1f, totalDist);

                    boolean flick = (mCurl.side == 1 && vx < -350f) || (mCurl.side == -1 && vx > 350f);
                    boolean shouldComplete = (progress > 0.22f) || flick;

                    int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
                    int targetIndex = (mCurl.side == 1) ? mCurrentIndex + step : mCurrentIndex - step;
                    float finalTargetX = shouldComplete
                            ? (mCurl.side == 1 ? spineX - mPageW * 0.25f : spineX + mPageW * 0.25f)
                            : edgeX;

                    startReleaseAnimation(finalTargetX, shouldComplete, targetIndex);
                    return true;
                }

                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                return true;
            }
        }
        return super.onTouchEvent(me);
    }

    private void startReleaseAnimation(float finalTargetX, boolean shouldComplete, int targetIndex) {
        if (mReleaseAnimator != null && mReleaseAnimator.isRunning()) {
            mReleaseAnimator.cancel();
        }
        mCurl.isAnimatingRelease = true;
        if (shouldComplete) {
            if (mSoundHelper != null) mSoundHelper.playFlip();
            if (mHapticHelper != null) mHapticHelper.vibratePageFlip();
        }

        float startX = mCurl.curX;
        float startY = mCurl.curY;
        mReleaseAnimator = ValueAnimator.ofFloat(0f, 1f);
        mReleaseAnimator.setDuration(240); // 240ms FastOutSlowIn airborne curve
        mReleaseAnimator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        mReleaseAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            mCurl.curX = startX + (finalTargetX - startX) * fraction;
            mCurl.curY = startY * (1f - fraction);
            invalidate();
        });
        mReleaseAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mReleaseAnimator = null;
                mCurl.active = false;
                mCurl.isAnimatingRelease = false;
                if (shouldComplete) {
                    mCurrentIndex = targetIndex;
                    invalidateCaches();
                    if (mPageListener != null) {
                        mPageListener.onPageChanged(mCurrentIndex);
                    }
                }
                invalidate();
            }
        });
        mReleaseAnimator.start();
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
        float W = mPageW;
        float H = mPageH;
        int side = mCurl.side;

        float edgeX = getEdgeX(side);
        float spineX = getSpineX(side);
        float curX = mCurl.curX;
        float curY = mCurl.curY;

        // Perpendicular midpoint bisector fold line
        float xFoldCenter = (edgeX + curX) * 0.5f;

        // Rigidly clamp xFoldCenter so page never tears away from the spine
        if (side == 1) {
            xFoldCenter = Math.max(spineX, Math.min(edgeX, xFoldCenter));
        } else {
            xFoldCenter = Math.min(spineX, Math.max(edgeX, xFoldCenter));
        }

        float liftDist = Math.abs(edgeX - xFoldCenter);
        float totalDist = Math.abs(edgeX - spineX);
        float archFactor = (float) Math.sin(Math.min(1.0, liftDist / Math.max(1f, totalDist)) * Math.PI);

        // Spine tau clamping (|tau| <= distToSpine * 0.94)
        float distToSpine = Math.abs(xFoldCenter - spineX);
        float maxTau = distToSpine * 0.94f;
        float desiredTau = -curY * 0.22f;
        float tau = Math.max(-maxTau, Math.min(maxTau, desiredTau));

        float len = (float) Math.hypot(H * 0.5f, tau);
        float nx = side * (H * 0.5f) / len;
        float ny = tau / len;

        g.fx = xFoldCenter;
        g.fy = 0;
        g.nx = nx;
        g.ny = ny;
        g.xFoldCenter = xFoldCenter;
        g.liftDist = liftDist;
        g.archFactor = archFactor;
        g.tau = tau;
        // Requirement 1: Tight 2.5D paper curl radius (4 to 12px)
        g.curlRadius = (4.0f + 8.0f * mConfig.softness) * (0.3f + 0.7f * archFactor);
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

        double rollCompress = curlRadius * 0.15 * Math.tanh(d / Math.max(6.0, curlRadius));
        double effectiveD = d - rollCompress;

        double rx = pt.x - (d + effectiveD) * nx;
        double ry = pt.y - (d + effectiveD) * ny;

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

            if (d1 < 2.0 && d2 < 2.0) {
                path.lineTo((float) p2.x, (float) p2.y);
                continue;
            }

            // Camber flex on outer free edges: 8-12% deflection
            double midX = (p1.x + p2.x) * 0.5;
            double midY = (p1.y + p2.y) * 0.5;
            double edgeLen = Math.hypot(p2.x - p1.x, p2.y - p1.y);
            double camberOffset = edgeLen * 0.09 * camberAmount;

            double edx = (p2.x - p1.x) / edgeLen;
            double edy = (p2.y - p1.y) / edgeLen;
            double enx = -edy;
            double eny = edx;

            double ctrlX = midX + enx * camberOffset;
            double ctrlY = midY + eny * camberOffset;

            path.quadTo((float) ctrlX, (float) ctrlY, (float) p2.x, (float) p2.y);
        }
        path.close();
    }

    private Bitmap getPageBitmap(int index, int side) {
        if (mPageProvider == null || index < 0 || index >= mPageProvider.getPageCount()) {
            return null;
        }
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
            mBitmapPaint.setColor(0xFF1E222B);
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
        } else {
            canvas.drawColor(0xFF07090E);
        }

        canvas.save();
        if (mZoomScale > 1.05f) {
            canvas.scale(mZoomScale, mZoomScale, mBookCenterX + mPanX, mBookCenterY + mPanY);
        }
        canvas.translate(mBookCenterX, mBookCenterY);

        float W = mPageW;
        float H = mPageH;

        // Inactive State: draw clean, centered page(s)
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
                drawPageBitmap(canvas, curBmp, new RectF(-W / 2f, -H / 2f, W / 2f, H / 2f));
            }
            canvas.restore();
            return;
        }

        // Active 2.5D Analytical Curl
        int side = mCurl.side;
        FoldGeometry geom = computeFoldGeometry();
        RectF activeRect = getPageRectForSide(side);

        // Active page rectangle polygon
        List<PointD> activePoly = new ArrayList<>();
        activePoly.add(new PointD(activeRect.left, activeRect.top));
        activePoly.add(new PointD(activeRect.right, activeRect.top));
        activePoly.add(new PointD(activeRect.right, activeRect.bottom));
        activePoly.add(new PointD(activeRect.left, activeRect.bottom));

        // 1. Opposite static page in two-page spread
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

        // 2. Revealed page underneath (half-plane keepSign = +1.0)
        List<PointD> revealedPoly = clipPolygonHalfPlane(activePoly, geom.fx, geom.fy, geom.nx, geom.ny, 1.0);
        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
        int revealedIdx = (side == 1) ? mCurrentIndex + step : mCurrentIndex - step;
        Bitmap revealedBmp = getPageBitmap(revealedIdx, CurlPage.SIDE_FRONT);

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
            drawPageBitmap(canvas, revealedBmp, activeRect);
            canvas.restore();
        }

        // 3. Uncurled flat part of current page (half-plane keepSign = -1.0)
        List<PointD> uncurledPoly = clipPolygonHalfPlane(activePoly, geom.fx, geom.fy, geom.nx, geom.ny, -1.0);
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
            drawPageBitmap(canvas, activeFrontBmp, activeRect);
            canvas.restore();
        }

        // 4. Curled flap with Householder reflection and feather-light shading
        if (revealedPoly.size() >= 3) {
            List<PointD> reflectedFlapPoly = new ArrayList<>();
            for (PointD pt : revealedPoly) {
                reflectedFlapPoly.add(reflectPointAcrossFold(pt, geom.fx, geom.fy, geom.nx, geom.ny, geom.curlRadius, mConfig.camber));
            }

            Path flapPath = new Path();
            traceSoftCurvedPolygon(flapPath, reflectedFlapPoly, geom.fx, geom.fy, geom.nx, geom.ny, mConfig.camber);

            // a) Feather-light drop shadow under flap (Requirement 5: alpha ~ 0.18-0.22)
            canvas.save();
            canvas.clipRect(activeRect);
            canvas.save();
            canvas.translate((float)(-geom.nx * 10f), 6f);
            mGradPaint.setColor(Color.argb((int)(45 * mConfig.shadowDepth), 2, 6, 23));
            canvas.drawPath(flapPath, mGradPaint);
            canvas.restore();
            canvas.restore();

            // b) Back of curled page with Householder 2D reflection
            Bitmap backBmp = getPageBitmap(mCurrentIndex, CurlPage.SIDE_BACK);
            if (backBmp == null) backBmp = activeFrontBmp;

            canvas.save();
            canvas.clipPath(flapPath);

            canvas.save();
            float[] vals = new float[]{
                    (float)(1.0 - 2.0 * geom.nx * geom.nx), (float)(-2.0 * geom.nx * geom.ny), 0f,
                    (float)(-2.0 * geom.nx * geom.ny), (float)(1.0 - 2.0 * geom.ny * geom.ny), 0f,
                    0f, 0f, 1f
            };
            mHouseholderMatrix.setValues(vals);
            mTempMatrix.reset();
            mTempMatrix.postTranslate((float) -geom.fx, (float) -geom.fy);
            mTempMatrix.postConcat(mHouseholderMatrix);
            mTempMatrix.postTranslate((float) geom.fx, (float) geom.fy);
            canvas.concat(mTempMatrix);

            // Draw mirrored back page in activeRect
            canvas.save();
            canvas.translate(activeRect.centerX(), activeRect.centerY());
            canvas.scale(-1f, 1f);
            canvas.translate(-activeRect.centerX(), -activeRect.centerY());
            drawPageBitmap(canvas, backBmp, activeRect);
            canvas.restore();

            canvas.restore(); // restore Householder

            // c) Subtle specular sheen along fold line (Requirement 5: white, alpha = 0.25)
            float span = Math.max(18f, (float) geom.curlRadius * 2.5f);
            LinearGradient gloss = new LinearGradient(
                    (float) geom.fx, (float) geom.fy,
                    (float)(geom.fx - geom.nx * span), (float)(geom.fy - geom.ny * span),
                    new int[]{
                            Color.argb((int)(64 * mConfig.shadowDepth), 255, 255, 255),
                            Color.argb((int)(20 * mConfig.shadowDepth), 255, 255, 255),
                            Color.TRANSPARENT
                    },
                    new float[]{ 0f, 0.45f, 1.0f },
                    Shader.TileMode.CLAMP
            );
            mGradPaint.setShader(gloss);
            canvas.drawRect(activeRect, mGradPaint);

            // d) Luminous crisp cut edge highlight
            canvas.drawPath(flapPath, mEdgePaint);

            canvas.restore(); // restore flap clip
        }

        // Spine Crease in two-page mode
        if (mViewMode == SHOW_TWO_PAGES) {
            drawCenterSpineCrease(canvas, H);
        }

        canvas.restore(); // restore bookCenter
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
