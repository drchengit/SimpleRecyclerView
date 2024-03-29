package com.loopeer.cardstack;

import android.view.View;

public class StackScrollDelegateImpl implements ScrollDelegate{

    private CardStackView mCardStackView;
    private int mScrollY;
    private int mScrollX;

    public StackScrollDelegateImpl(CardStackView cardStackView) {
        mCardStackView = cardStackView;
    }

    /**
     * 根据回调的坐标更新每个item 的实时位置
     */
    private void updateChildPos() {
        for (int i = 0; i < mCardStackView.getChildCount(); i++) {
            View view = mCardStackView.getChildAt(i);
            if (view.getTop() - mScrollY < mCardStackView.getChildAt(0).getY()) {
                view.setTranslationY(mCardStackView.getChildAt(0).getY() - view.getTop());
            } else if (view.getTop() - mScrollY > view.getTop()) {
                view.setTranslationY(0);
            } else {
                view.setTranslationY(-mScrollY);
            }
        }
    }

    /**
     *
     * @param x
     * @param y   滑动过程中的滑动距离， 滑动的回调
     */
    @Override
    public void scrollViewTo(int x, int y) {
        x = clamp(x, mCardStackView.getWidth() - mCardStackView.getPaddingRight() - mCardStackView.getPaddingLeft(), mCardStackView.getWidth());
        y = clamp(y, mCardStackView.getShowHeight(), mCardStackView.getTotalLength());
        mScrollY = y;
        mScrollX = x;
        updateChildPos();
    }

    private static int clamp(int n, int my, int child) {
        if (my >= child || n < 0) {
            return 0;
        }
        if ((my + n) > child) {
            return child - my;
        }
        return n;
    }

    @Override
    public void setViewScrollY(int y) {
        scrollViewTo(mScrollX, y);
    }

    @Override
    public void setViewScrollX(int x) {
        scrollViewTo(x, mScrollY);
    }

    @Override
    public int getViewScrollY() {
        return mScrollY;
    }

    @Override
    public int getViewScrollX() {
        return mScrollX;
    }
}
