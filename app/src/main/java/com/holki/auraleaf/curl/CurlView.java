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
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import com.holki.auraleaf.HapticHelper;
import com.holki.auraleaf.SoundHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * RealisticBookEngine for AuraLeaf Feather.
 * 100% Faithful Port of the 2.5D Analytical Projective Geometry & 2nd-Order Spring-Damper Engine:
 *  - 2nd-Order Differential Spring-Mass-Damper Equations (Hooke's Law with Viscous Damping)
 *  - Midpoint Bisector Fold Line with Roll Compression (tanh) & Velocity Speed Bend
 *  - Elastic Quadratic Bezier Camber Flexion
 *  - Edge-Zone Tapping (Right -> Next, Left -> Prev, Center -> Menu)
 *  - Direction-Based Swipe Detection (Left -> Next, Right -> Prev, Zero Time-Loops)
 *  - Effortless 28% Flip Completion Threshold with Flick Gesture Support
 *  - Clean Unmirrored Text and Symmetrical Fullscreen Layout
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

    // Touch & Swipe tracking
    private float mDownScreenX = 0f;
    private float mDownScreenY = 0f;
    private long mDownTime = 0;
    private boolean mIsDragging = false;
    private VelocityTracker mVelocityTracker;

    // RealisticBookEngine Config (Exact values from HTML)
    public static class Config {
        public float softness = 0.85f;     // Softness of paper roll
        public float fluidity = 0.78f;     // Fluidity of spring-damper tracking
        public float camber = 0.75f;       // Elastic Bezier camber flexion
        public float shadowDepth = 0.90f;  // Intensity of 3D shadows and specular shine
    }
    private final Config mConfig = new Config();

    // Curl State
    private static class CurlState {
        boolean active = false;
        boolean isDragging = false;
        boolean isHovering = false;
        boolean isAnimatingRelease = false;
        int side = 1; // +1 = forward (left swipe), -1 = backward (right swipe)

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
    private float mPageW = 400f;
    private float mPageH = 600f;
    private long mLastPhysicsTime = 0;

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
        mEdgePaint.setStrokeWidth(2.0f);
        mEdgePaint.setColor(Color.argb(140, 226, 232, 240));

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
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

        if (mViewMode == SHOW_TWO_PAGES) {
            mPageW = viewW / 2f;
            mPageH = viewH;
        } else {
            // Fullscreen single page
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

    // Programmatic flip forward (from HTML flipToSpread)
    public void flipNext() {
        if (mPageProvider == null || mCurl.isDragging) return;
        int total = mPageProvider.getPageCount();
        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
        if (mCurrentIndex + step > total - 1 && mCurrentIndex >= total - 1) return;

        startProgrammaticFlip(1, mCurrentIndex + step);
    }

    // Programmatic flip backward (from HTML flipToSpread)
    public void flipPrev() {
        if (mPageProvider == null || mCurl.isDragging) return;
        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
        if (mCurrentIndex <= 0) return;

        startProgrammaticFlip(-1, Math.max(0, mCurrentIndex - step));
    }

    private void startProgrammaticFlip(int side, int targetIndex) {
        float W = mPageW;
        float H = mPageH;

        mCurl.active = true;
        mCurl.isDragging = false;
        mCurl.isHovering = false;
        mCurl.isAnimatingRelease = true;
        mCurl.side = side;

        if (side == 1) {
            mCurl.grabX = W * 0.88f;
            mCurl.grabY = H * 0.28f;
            mCurl.curX = W * 0.85f;
            mCurl.curY = H * 0.26f;
            mCurl.velX = -W * 2.8f;
            mCurl.velY = -H * 0.40f;
            mCurl.targetX = -W;
            mCurl.targetY = 0f;
        } else {
            mCurl.grabX = W * 0.12f;
            mCurl.grabY = H * 0.28f;
            mCurl.curX = W * 0.15f;
            mCurl.curY = H * 0.26f;
            mCurl.velX = W * 2.8f;
            mCurl.velY = -H * 0.40f;
            mCurl.targetX = 2f * W;
            mCurl.targetY = 0f;
        }

        mCurl.completeFlipOnFinish = true;
        mCurl.targetIndexAfterFlip = targetIndex;

        if (mSoundHelper != null) mSoundHelper.playFlip();
        if (mHapticHelper != null) mHapticHelper.vibratePageFlip();
        mLastPhysicsTime = System.nanoTime();
        postInvalidateOnAnimation();
    }

    private float screenToBookY(float screenY) {
        return screenY - getHeight() / 2f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent me) {
        if (mGestureDetector != null) {
            mGestureDetector.onTouchEvent(me);
        }
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
                mDownScreenX = me.getX();
                mDownScreenY = me.getY();
                mDownTime = System.currentTimeMillis();
                mIsDragging = false;

                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                } else {
                    mVelocityTracker.clear();
                }
                mVelocityTracker.addMovement(me);
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mVelocityTracker != null) {
                    mVelocityTracker.addMovement(me);
                }

                float dx = me.getX() - mDownScreenX;
                float dy = me.getY() - mDownScreenY;

                // Only start drag when finger movement exceeds touch slop
                if (!mIsDragging) {
                    if (Math.hypot(dx, dy) > mTouchSlop) {
                        int side;
                        if (mViewMode == SHOW_TWO_PAGES) {
                            side = (mDownScreenX >= getWidth() / 2f) ? 1 : -1;
                        } else {
                            // Single page mode: direction is strictly determined by drag vector dx!
                            // Dragging left (dx < 0) = Flip Next (side = 1)
                            // Dragging right (dx > 0) = Flip Prev (side = -1)
                            side = (dx < 0) ? 1 : -1;
                        }

                        int total = mPageProvider != null ? mPageProvider.getPageCount() : 0;
                        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;
                        if (side == 1 && mCurrentIndex >= total - 1) return true;
                        if (side == -1 && mCurrentIndex <= 0) return true;

                        mIsDragging = true;
                        handleGrabStart(side, mDownScreenX, mDownScreenY);
                    }
                }

                if (mIsDragging) {
                    handleGrabMove(me.getX(), me.getY());
                    return true;
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (mVelocityTracker != null) {
                    mVelocityTracker.addMovement(me);
                    mVelocityTracker.computeCurrentVelocity(1000);
                }
                float vx = (mVelocityTracker != null) ? mVelocityTracker.getXVelocity() : 0f;
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }

                if (mIsDragging) {
                    mIsDragging = false;
                    handleGrabEnd(vx);
                    return true;
                }

                // If not dragging, handle as a TAP
                long duration = System.currentTimeMillis() - mDownTime;
                if (duration < 450) {
                    handleTap(me.getX(), me.getY());
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                if (mIsDragging) {
                    mIsDragging = false;
                    handleGrabEnd(0f);
                }
                return true;
            }
        }
        return super.onTouchEvent(me);
    }

    // Handles tap events: edge zones flip pages, center toggles menu
    private void handleTap(float x, float y) {
        if (mZoomScale > 1.05f) return;
        float viewW = getWidth();
        if (viewW <= 0) return;

        if (x > viewW * 0.72f) {
            // Right 28% -> Next Page
            flipNext();
        } else if (x < viewW * 0.28f) {
            // Left 28% -> Previous Page
            flipPrev();
        } else {
            // Middle 44% -> Menu / UI Toggle
            if (mTapListener != null) {
                mTapListener.onSingleTap();
            }
        }
    }

    // Grab start with direction-aware edge initialization
    private void handleGrabStart(int side, float startX, float startY) {
        float W = mPageW;
        float H = mPageH;

        mCurl.active = true;
        mCurl.isDragging = true;
        mCurl.isHovering = false;
        mCurl.isAnimatingRelease = false;
        mCurl.side = side;

        mCurl.grabY = Math.max(-H / 2f, Math.min(H / 2f, screenToBookY(startY)));
        mCurl.pressStrength = 1.0f;

        float initialLift = W * (0.12f + 0.06f * mConfig.softness);

        if (side == 1) {
            // Next page: curl starts from right edge W towards left
            mCurl.grabX = W;
            mCurl.curX = W;
            mCurl.curY = mCurl.grabY;
            mCurl.targetX = W - initialLift;
            mCurl.targetY = mCurl.grabY * 0.92f;
        } else {
            // Prev page: curl starts from left edge 0 towards right
            mCurl.grabX = 0f;
            mCurl.curX = 0f;
            mCurl.curY = mCurl.grabY;
            mCurl.targetX = initialLift;
            mCurl.targetY = mCurl.grabY * 0.92f;
        }

        mCurl.velX = 0f;
        mCurl.velY = 0f;

        if (mHapticHelper != null) mHapticHelper.vibratePageTouch();
        mLastPhysicsTime = System.nanoTime();
        postInvalidateOnAnimation();
    }

    // Drag move with normalized span mapping
    private void handleGrabMove(float currentX, float currentY) {
        float W = mPageW;
        float H = mPageH;
        int side = mCurl.side;

        float dx = currentX - mDownScreenX;
        float dy = currentY - mDownScreenY;

        float span = W * 0.85f;
        float baseLift = W * 0.10f;

        if (side == 1) {
            // Dragging left: dx is negative
            float dragDist = Math.max(0f, -dx);
            float progress = dragDist / span;
            float mappedX = W - baseLift - progress * (2.1f * W - baseLift);
            mCurl.targetX = Math.max(-W * 1.05f, Math.min(W * 1.05f, mappedX));
        } else {
            // Dragging right: dx is positive
            float dragDist = Math.max(0f, dx);
            float progress = dragDist / span;
            float mappedX = baseLift + progress * (2.1f * W - baseLift);
            mCurl.targetX = Math.max(-W * 0.05f, Math.min(W * 2.05f, mappedX));
        }

        mCurl.targetY = Math.max(-H * 0.85f, Math.min(H * 0.85f, mCurl.grabY + dy * 1.15f));
        postInvalidateOnAnimation();
    }

    // Effortless 28% completion threshold with flick gesture detection
    private void handleGrabEnd(float vx) {
        mCurl.isDragging = false;
        mCurl.isHovering = false;
        mCurl.isAnimatingRelease = true;
        mCurl.pressStrength = 0f;

        float W = mPageW;
        int side = mCurl.side;

        int step = (mViewMode == SHOW_TWO_PAGES) ? 2 : 1;

        if (side == 1) {
            float flipVelocity = -vx; // positive if flicked left
            boolean isFlick = flipVelocity > 350f;
            // Complete if page moved past 28% of screen OR flicked
            boolean shouldComplete = (mCurl.curX < W * 0.72f) || isFlick;

            if (shouldComplete) {
                mCurl.completeFlipOnFinish = true;
                mCurl.targetIndexAfterFlip = mCurrentIndex + step;
                mCurl.targetX = -W;
                mCurl.targetY = 0f;
                mCurl.velX = Math.min(mCurl.velX, -W * 1.8f);
                if (mSoundHelper != null) mSoundHelper.playFlip();
                if (mHapticHelper != null) mHapticHelper.vibratePageFlip();
            } else {
                mCurl.completeFlipOnFinish = false;
                mCurl.targetX = W;
                mCurl.targetY = mCurl.grabY * 0.5f;
                mCurl.velX = Math.max(mCurl.velX, W * 1.2f);
            }
        } else {
            float flipVelocity = vx; // positive if flicked right
            boolean isFlick = flipVelocity > 350f;
            // Complete if page moved past 28% of screen OR flicked
            boolean shouldComplete = (mCurl.curX > W * 0.28f) || isFlick;

            if (shouldComplete) {
                mCurl.completeFlipOnFinish = true;
                mCurl.targetIndexAfterFlip = mCurrentIndex - step;
                mCurl.targetX = 2f * W;
                mCurl.targetY = 0f;
                mCurl.velX = Math.max(mCurl.velX, W * 1.8f);
                if (mSoundHelper != null) mSoundHelper.playFlip();
                if (mHapticHelper != null) mHapticHelper.vibratePageFlip();
            } else {
                mCurl.completeFlipOnFinish = false;
                mCurl.targetX = 0f;
                mCurl.targetY = mCurl.grabY * 0.5f;
                mCurl.velX = Math.min(mCurl.velX, -W * 1.2f);
            }
        }
        postInvalidateOnAnimation();
    }

    // 2nd-Order Spring-Damper Physics Step (Hooke's Law with Viscous Damping from HTML)
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

    // Exact HTML computeFoldGeometry implementation
    private FoldGeometry computeFoldGeometry() {
        FoldGeometry g = new FoldGeometry();
        double W = mPageW;
        double H = mPageH;
        int side = mCurl.side;

        double xFoldCenter;
        double liftDist;
        double distToSpine;

        if (side == 1) {
            // Next page: spine at 0, edge at W
            double px = Math.max(-W, Math.min(W - 1, mCurl.curX));
            xFoldCenter = Math.max(0.001, Math.min(W - 0.5, (W + px) * 0.5));
            liftDist = W - xFoldCenter;
            distToSpine = xFoldCenter;
        } else {
            // Prev page: spine at W, edge at 0
            double px = Math.max(1, Math.min(2 * W, mCurl.curX));
            xFoldCenter = Math.max(0.5, Math.min(W - 0.001, px * 0.5));
            liftDist = xFoldCenter;
            distToSpine = W - xFoldCenter;
        }

        double py = mCurl.curY;
        double gy = mCurl.grabY;

        double verticalPull = (py - gy) * 0.85 + gy * (liftDist / W < 0.45 ? 0.95 : 0.45);
        double desiredTau = -verticalPull * (0.65 + 0.35 * mConfig.softness);

        // Dynamic Spine Tau Clamping
        double maxSpineTau = distToSpine * 0.96;
        double tau = Math.max(-maxSpineTau, Math.min(maxSpineTau, desiredTau));

        double len = Math.hypot(H * 0.5, tau);
        double nx = (side == 1) ? ((H * 0.5) / len) : (-(H * 0.5) / len);
        double ny = tau / len;

        g.fx = xFoldCenter;
        g.fy = 0;
        g.nx = nx;
        g.ny = ny;
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

    // Householder 2D Reflection with Roll Compression & Speed Bend (Exact HTML)
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

    // Quadratic Bezier Camber Elastic Edge Flexion (Exact HTML)
    private void traceSoftCurvedPolygon(Path path, List<PointD> poly, double fx, double fy, double nx, double ny, double camberAmount, double archFactor) {
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
                continue;
            }

            double midX = (p1.x + p2.x) * 0.5;
            double midY = (p1.y + p2.y) * 0.5;
            double edgeLen = Math.hypot(p2.x - p1.x, p2.y - p1.y);
            double camberOffset = edgeLen * 0.14 * camberAmount * archFactor;

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

        // Physics time integration
        long now = System.nanoTime();
        if (mLastPhysicsTime > 0) {
            float dt = Math.min(0.035f, (now - mLastPhysicsTime) / 1_000_000_000f);
            stepPhysics(dt);
        }
        mLastPhysicsTime = now;

        canvas.save();
        if (mZoomScale > 1.05f) {
            canvas.scale(mZoomScale, mZoomScale, getWidth() / 2f + mPanX, getHeight() / 2f + mPanY);
        }

        float W = mPageW;
        float H = mPageH;

        if (mViewMode == SHOW_TWO_PAGES) {
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
        } else {
            // Fullscreen single page: starts at x = 0
            canvas.translate(0f, getHeight() / 2f);
        }

        // Static Pages when Curl is Inactive
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

        // Active 2.5D RealisticBookEngine Curl
        int side = mCurl.side;
        FoldGeometry geom = computeFoldGeometry();
        double fx = geom.fx, fy = geom.fy, nx = geom.nx, ny = geom.ny;
        double curlRadius = geom.curlRadius, archFactor = geom.archFactor;

        // Base page rect [0, W]
        List<PointD> activePoly = new ArrayList<>();
        activePoly.add(new PointD(0, -H / 2f));
        activePoly.add(new PointD(W, -H / 2f));
        activePoly.add(new PointD(W, H / 2f));
        activePoly.add(new PointD(0, H / 2f));

        // 1. Opposite static page in two-page mode
        if (mViewMode == SHOW_TWO_PAGES) {
            int leftIdx = (mCurrentIndex % 2 == 1) ? mCurrentIndex : mCurrentIndex - 1;
            Bitmap leftBmp = getPageBitmap(leftIdx, CurlPage.SIDE_FRONT);
            if (leftBmp != null) drawPageBitmap(canvas, leftBmp, new RectF(-W, -H / 2f, 0, H / 2f));
        }

        // 2. Revealed page underneath (half-plane keepSign = +1.0)
        List<PointD> revealedPoly = clipPolygonHalfPlane(activePoly, fx, fy, nx, ny, 1.0);
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
            drawPageBitmap(canvas, revealedBmp, new RectF(0, -H / 2f, W, H / 2f));
            canvas.restore();
        }

        // 3. Uncurled flat part of current page (half-plane keepSign = -1.0)
        List<PointD> uncurledPoly = clipPolygonHalfPlane(activePoly, fx, fy, nx, ny, -1.0);
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
            drawPageBitmap(canvas, activeFrontBmp, new RectF(0, -H / 2f, W, H / 2f));

            // Pre-fold gradient along fold axis from HTML
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
            canvas.drawRect(new RectF(0, -H / 2f, W, H / 2f), mGradPaint);

            // Tactile touch point depression wave from HTML
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
                canvas.drawRect(new RectF(0, -H / 2f, W, H / 2f), mGradPaint);
            }
            canvas.restore();
        }

        // 4. Center Spine Crease
        if (mViewMode == SHOW_TWO_PAGES) {
            drawCenterSpineCrease(canvas, H);
        }

        // 5. Cast Soft Drop Shadow & Curled Flap with Householder Reflection (Exact HTML)
        if (revealedPoly.size() >= 3) {
            List<PointD> reflectedFlapPoly = new ArrayList<>();
            for (PointD pt : revealedPoly) {
                reflectedFlapPoly.add(reflectPointAcrossFold(pt, fx, fy, nx, ny, curlRadius, mConfig.camber));
            }

            // a) Cast Drop Shadow
            double shadowSpread = (18.0 + 55.0 * archFactor) * mConfig.shadowDepth;
            Path shadowPath = new Path();
            traceSoftCurvedPolygon(shadowPath, reflectedFlapPoly, fx, fy, nx, ny, mConfig.camber, archFactor);

            canvas.save();
            canvas.clipRect(new RectF(-W * 1.5f, -H, W * 3f, H * 2f));
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

            // b) Back of Curled Flap with Householder Reflection
            Path flapPath = new Path();
            traceSoftCurvedPolygon(flapPath, reflectedFlapPoly, fx, fy, nx, ny, mConfig.camber, archFactor);

            Bitmap backBmp = getPageBitmap(mCurrentIndex, CurlPage.SIDE_BACK);
            if (backBmp == null) backBmp = activeFrontBmp;

            canvas.save();
            canvas.clipPath(flapPath);

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

            // Draw mirrored back page across its horizontal center
            canvas.save();
            canvas.translate(W / 2f, 0f);
            canvas.scale(-1f, 1f);
            canvas.translate(-W / 2f, 0f);
            drawPageBitmap(canvas, backBmp, new RectF(0, -H / 2f, W, H / 2f));
            canvas.restore();

            canvas.restore(); // restore Householder

            // Cylindrical 3D specular highlight and gradient from HTML
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
            canvas.drawRect(new RectF(-W * 1.5f, -H, W * 3f, H * 2f), mGradPaint);

            // Luminous cut paper edge highlight
            canvas.drawPath(flapPath, mEdgePaint);

            canvas.restore(); // restore flap clip
        }

        canvas.restore(); // restore bookCenter

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
