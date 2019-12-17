package com.loopeer.cardstack;

import android.annotation.TargetApi;
import android.content.Context;
import android.database.Observable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

public class CardStackView extends ViewGroup implements ScrollDelegate {

    private static final int INVALID_POINTER = -1;
    private final ViewDataObserver mObserver = new ViewDataObserver();
    private final String TAG = "CardStackView";
    private int mTotalLength;//内容item长总高度
    private StackAdapter mStackAdapter;
    private int mShowHeight;// view 显示区域高
    private List<ViewHolder> mViewHolders;
    private OverScroller mScroller;//负责惯性回弹
    private int mLastMotionY;//上一次
    private boolean mIsBeingDragged = false;
    private VelocityTracker mVelocityTracker;
    private int mTouchSlop;//触发移动事件的最小距离
    private int mMinimumVelocity;// 判定速度
    private int mMaximumVelocity;
    private int mActivePointerId = INVALID_POINTER; //触点id
    private int mNestedYOffset;
    private boolean mScrollEnable = true;//是否允许滑动
    private ScrollDelegate mScrollDelegate;

    public CardStackView(Context context) {
        this(context, null);
    }

    public CardStackView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CardStackView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr, 0);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        mViewHolders = new ArrayList<>();
        initScroller();
        mScrollDelegate = this;

    }

    private void initScroller() {
        mScroller = new OverScroller(getContext());
        setFocusable(true);
        //先分发给Child View进行处理，如果所有的Child View都沒有处理，則自己再处理
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public CardStackView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs, defStyleAttr, defStyleRes);
    }

    public void setAdapter(StackAdapter stackAdapter) {
        mStackAdapter = stackAdapter;
        mStackAdapter.registerObserver(mObserver);
        refreshView();
    }

    private void refreshView() {
        removeAllViews();
        mViewHolders.clear();
        for (int i = 0; i < mStackAdapter.getItemCount(); i++) {
            ViewHolder holder = getViewHolder(i);
            holder.position = i;

            addView(holder.itemView);
            mStackAdapter.bindViewHolder(holder, i);
        }
        requestLayout();
    }

    ViewHolder getViewHolder(int i) {
        ViewHolder viewHolder;
        if (mViewHolders.size() <= i || mViewHolders.get(i).mItemViewType != mStackAdapter.getItemViewType(i)) {
            viewHolder = mStackAdapter.createView(this, mStackAdapter.getItemViewType(i));
            mViewHolders.add(viewHolder);
        } else {
            viewHolder = mViewHolders.get(i);
        }
        return viewHolder;
    }

    /**
     * 整个方法是，滑动交给自己touch处理（包括屏蔽parent），非滑动，交给子item处理
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        //运动中 并 在滑动中的 被拦截为
        if ((action == MotionEvent.ACTION_MOVE) && (mIsBeingDragged)) {
            return true;
        }
        // 没有滑动 并 不能滑动 给子view
        if (getViewScrollY() == 0 && !canScrollVertically(1)) {
            return false;
        }

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                final int activePointerId = mActivePointerId;
                if (activePointerId == INVALID_POINTER) {
                    break;
                }

                final int pointerIndex = ev.findPointerIndex(activePointerId);
                if (pointerIndex == -1) {
                    Log.e(TAG, "Invalid pointerId=" + activePointerId
                            + " in onInterceptTouchEvent");
                    break;
                }

                final int y = (int) ev.getY(pointerIndex);
                final int yDiff = Math.abs(y - mLastMotionY);//触点y 与 上一次触点的y
                if (yDiff > mTouchSlop) {//大于，判断开始滑动
                    mIsBeingDragged = true;
                    mLastMotionY = y;
                    initVelocityTrackerIfNotExists();
                    mVelocityTracker.addMovement(ev);
                    mNestedYOffset = 0;
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);//屏蔽父类事件
                    }
                }
                break;
            }

            case MotionEvent.ACTION_DOWN: {
                final int y = (int) ev.getY();
                mLastMotionY = y;
                mActivePointerId = ev.getPointerId(0);// 得到触摸的单点
                initOrResetVelocityTracker();  // 重置手势速度的那东西
                mVelocityTracker.addMovement(ev);
                mIsBeingDragged = !mScroller.isFinished();// 滑动中状态
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP://滑动完成
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                recycleVelocityTracker();
                if (mScroller.springBack(getViewScrollX(), getViewScrollY(), 0, 0, 0, getScrollRange())) {
                    postInvalidate();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }
        if (!mScrollEnable) {
            mIsBeingDragged = false;
        }
        return mIsBeingDragged;
    }

    private void initVelocityTrackerIfNotExists() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }

    private void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private int getScrollRange() {
        int scrollRange = 0;
        if (getChildCount() > 0) {
            // item 实际超过显示区域的长度 到 0  零之间随机一个惯性数
            scrollRange = Math.max(0, mTotalLength - mShowHeight);
        }
        return scrollRange;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = (ev.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK) >>
                MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastMotionY = (int) ev.getY(newPointerIndex);
            mActivePointerId = ev.getPointerId(newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }



    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutChild();
    }

    private void layoutChild() {
        int childTop = getPaddingTop();
        int childLeft = getPaddingLeft();

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            final int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();

            final LayoutParams lp =
                    (LayoutParams) child.getLayoutParams();
            childTop += lp.topMargin;
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
            childTop += childHeight;
        }
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * 自己处理滑动
     */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mIsBeingDragged) {//没有在 滑动中
            super.onTouchEvent(ev);
        }
        if (!mScrollEnable) {//不能滑动 ，直接消耗掉事件
            return true;
        }


        initVelocityTrackerIfNotExists();//检测速度那个初始化

        MotionEvent vtev = MotionEvent.obtain(ev);

        final int actionMasked = ev.getActionMasked();//动作

        if (actionMasked == MotionEvent.ACTION_DOWN) {
            mNestedYOffset = 0;
        }
        vtev.offsetLocation(0, mNestedYOffset);

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                if (getChildCount() == 0) {//没有item 自然跳过
                    return false;
                }
                if ((mIsBeingDragged = !mScroller.isFinished())) {//正在滑动，屏蔽父布局
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (!mScroller.isFinished()) {//点击时，正在动画，暂停动画
                    mScroller.abortAnimation();
                }
                mLastMotionY = (int) ev.getY();//位置
                mActivePointerId = ev.getPointerId(0);//触点
                break;
            }
            case MotionEvent.ACTION_MOVE:
                final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                if (activePointerIndex == -1) {
                    Log.e(TAG, "Invalid pointerId=" + mActivePointerId + " in onTouchEvent");
                    break;
                }

                final int y = (int) ev.getY(activePointerIndex);
                int deltaY = mLastMotionY - y;
                if (!mIsBeingDragged && Math.abs(deltaY) > mTouchSlop) {//没有滑动，但是达到了滑动条件
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);//屏蔽
                    }
                    mIsBeingDragged = true;
                    //下面是滑动距离计算，跟最小滑动距离挂勾
                    if (deltaY > 0) {
                        deltaY -= mTouchSlop;
                    } else {
                        deltaY += mTouchSlop;
                    }
                }
                if (mIsBeingDragged) {// 滑动中
                    mLastMotionY = y;
                    final int range = getScrollRange();//惯性的长度
                    if (mScrollDelegate instanceof StackScrollDelegateImpl) {
                        mScrollDelegate.scrollViewTo(0, deltaY + mScrollDelegate.getViewScrollY());
                    } else {
                        if (overScrollBy(0, deltaY, 0, getViewScrollY(),
                                0, range, 0, 0, true)) {
                            mVelocityTracker.clear();
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mIsBeingDragged) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                    // 一秒内 完成指定 距离的速度
                    int initialVelocity = (int) velocityTracker.getYVelocity(mActivePointerId);
                    if (getChildCount() > 0) {
                        if ((Math.abs(initialVelocity) > mMinimumVelocity)) {
                            fling(-initialVelocity);//超速 惯性滑动
                        } else {
                            //没超速，惯性回弹
                            if (mScroller.springBack(getViewScrollX(), mScrollDelegate.getViewScrollY(), 0, 0, 0,
                                    getScrollRange())) {
                                postInvalidate();
                            }
                        }
                        mActivePointerId = INVALID_POINTER;
                    }
                }
                endDrag();//结束滑动状态
                break;
            case MotionEvent.ACTION_CANCEL:
                // 取消惯性回弹
                if (mIsBeingDragged && getChildCount() > 0) {
                    if (mScroller.springBack(getViewScrollX(), mScrollDelegate.getViewScrollY(), 0, 0, 0, getScrollRange())) {
                        postInvalidate();
                    }
                    mActivePointerId = INVALID_POINTER;
                }
                endDrag();
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int index = ev.getActionIndex();
                mLastMotionY = (int) ev.getY(index);
                mActivePointerId = ev.getPointerId(index);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                mLastMotionY = (int) ev.getY(ev.findPointerIndex(mActivePointerId));
                break;
        }

        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(vtev);
        }
        vtev.recycle();
        return true;
    }

    public void fling(int velocityY) {
        if (getChildCount() > 0) {
            int height = mShowHeight;
            int bottom = mTotalLength;
            mScroller.fling(mScrollDelegate.getViewScrollX(), mScrollDelegate.getViewScrollY(), 0, velocityY, 0, 0, 0,
                    Math.max(0, bottom - height), 0, 0);
            postInvalidate();
        }
    }

    private void endDrag() {
        mIsBeingDragged = false;
        recycleVelocityTracker();
    }

    @Override
    public void scrollTo(int x, int y) {
        if (getChildCount() > 0) {
            x = clamp(x, getWidth() - getPaddingRight() - getPaddingLeft(), getWidth());
            y = clamp(y, mShowHeight, mTotalLength);
            if (x != mScrollDelegate.getViewScrollX() || y != mScrollDelegate.getViewScrollY()) {
                super.scrollTo(x, y);
            }
        }
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            mScrollDelegate.scrollViewTo(0, mScroller.getCurrY());
            postInvalidate();
        }
    }

    @Override
    protected int computeVerticalScrollRange() {
        final int count = getChildCount();
        final int contentHeight = mShowHeight;
        if (count == 0) {
            return contentHeight;
        }

        int scrollRange = mTotalLength;
        final int scrollY = mScrollDelegate.getViewScrollY();
        final int overscrollBottom = Math.max(0, scrollRange - contentHeight);
        if (scrollY < 0) {
            scrollRange -= scrollY;
        } else if (scrollY > overscrollBottom) {
            scrollRange += scrollY - overscrollBottom;
        }

        return scrollRange;
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return Math.max(0, super.computeVerticalScrollOffset());
    }

    /**
     * 测量得到  （可显示区域的长度） 和 （所有item长度的加起来的实际长度）
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        checkContentHeightByParent();
        measureChild(widthMeasureSpec, heightMeasureSpec);
    }    @Override
    public int getViewScrollX() {
        return getScrollX();
    }

    private void checkContentHeightByParent() {
        View parentView = (View) getParent();
        mShowHeight = parentView.getMeasuredHeight() - parentView.getPaddingTop() - parentView.getPaddingBottom();
        Log.e("测量mShowHeight",mShowHeight+"");
    }

    private void measureChild(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = 0;
        mTotalLength = 0;
        mTotalLength += getPaddingTop() + getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            final int totalLength = mTotalLength;
            final LayoutParams lp =
                    (LayoutParams) child.getLayoutParams();
            final int childHeight = child.getMeasuredHeight();
            mTotalLength = Math.max(totalLength, totalLength + childHeight + lp.topMargin +
                    lp.bottomMargin);
//            mTotalLength -= mOverlapGaps * 2;
            final int margin = lp.leftMargin + lp.rightMargin;
            final int measuredWidth = child.getMeasuredWidth() + margin;
            maxWidth = Math.max(maxWidth, measuredWidth);
        }

//        mTotalLength += mOverlapGaps * 2;
        int heightSize = mTotalLength;
        heightSize = Math.max(heightSize, mShowHeight);
        int heightSizeAndState = resolveSizeAndState(heightSize, heightMeasureSpec, 0);
        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, 0),
                heightSizeAndState);
        Log.e("测量mTotalLength",mTotalLength+"");
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY,
                                  boolean clampedX, boolean clampedY) {
        if (!mScroller.isFinished()) {
            final int oldX = mScrollDelegate.getViewScrollX();
            final int oldY = mScrollDelegate.getViewScrollY();
            mScrollDelegate.setViewScrollX(scrollX);
            mScrollDelegate.setViewScrollY(scrollY);
            onScrollChanged(mScrollDelegate.getViewScrollX(), mScrollDelegate.getViewScrollY(), oldX, oldY);
            if (clampedY) {
                mScroller.springBack(mScrollDelegate.getViewScrollX(), mScrollDelegate.getViewScrollY(), 0, 0, 0, getScrollRange());
            }
        } else {
            super.scrollTo(scrollX, scrollY);
        }
    }    @Override
    public void setViewScrollY(int y) {
        setScrollY(y);
    }

    @Override
    public void scrollViewTo(int x, int y) {
        scrollTo(x, y);
    }

    private static int clamp(int n, int my, int child) {
        if (my >= child || n < 0) {
            return 0;
        }
        if ((my + n) > child) {
            return child - my;
        }
        return n;
    }    @Override
    public void setViewScrollX(int x) {
        setScrollX(x);
    }

    public void setScrollEnable(boolean scrollEnable) {
        mScrollEnable = scrollEnable;
    }

    public int getShowHeight() {
        return mShowHeight;
    }    @Override
    public int getViewScrollY() {
        return getScrollY();
    }

    public int getTotalLength() {
        return mTotalLength;
    }

    public ScrollDelegate getScrollDelegate() {
        return mScrollDelegate;
    }

    public static class LayoutParams extends MarginLayoutParams {


        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }
    }

    public static abstract class Adapter<VH extends ViewHolder> {
        private final AdapterDataObservable mObservable = new AdapterDataObservable();

        VH createView(ViewGroup parent, int viewType) {
            VH holder = onCreateView(parent, viewType);
            holder.mItemViewType = viewType;
            return holder;
        }

        protected abstract VH onCreateView(ViewGroup parent, int viewType);

        public void bindViewHolder(VH holder, int position) {
            onBindViewHolder(holder, position);
        }

        protected abstract void onBindViewHolder(VH holder, int position);

        public abstract int getItemCount();

        public int getItemViewType(int position) {
            return 0;
        }

        public final void notifyDataSetChanged() {
            mObservable.notifyChanged();
        }

        public void registerObserver(AdapterDataObserver observer) {
            mObservable.registerObserver(observer);
        }
    }

    public static abstract class ViewHolder {

        public View itemView;
        int mItemViewType = -1;
        int position;

        public ViewHolder(View view) {
            itemView = view;
        }

        public Context getContext() {
            return itemView.getContext();
        }


    }

    public static class AdapterDataObservable extends Observable<AdapterDataObserver> {
        public boolean hasObservers() {
            return !mObservers.isEmpty();
        }

        public void notifyChanged() {
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onChanged();
            }
        }
    }

    public static abstract class AdapterDataObserver {
        public void onChanged() {
        }
    }

    private class ViewDataObserver extends AdapterDataObserver {
        @Override
        public void onChanged() {
            refreshView();
        }
    }










}
