package com.vineyard.hfm.app;

import android.content.Context;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class DragSelectTouchListener implements RecyclerView.OnItemTouchListener {

    private final GestureDetector mGestureDetector;
    private final OnDragSelectListener mListener;
    private boolean mIsDragging;
    private int mStart, mEnd;
    private int mScrollAmount;
    private final int mAutoScrollDistance;
    private final Handler mAutoScrollHandler = new Handler();
    private boolean mIsAutoScrolling;
    private RecyclerView mRecyclerView;

    public interface OnDragSelectListener {
        void onSelectChange(int start, int end, boolean isSelected);
    }

    private final Runnable mAutoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsAutoScrolling) {
                if (mRecyclerView != null && mScrollAmount != 0) {
                    mRecyclerView.scrollBy(0, mScrollAmount);
                }
                mAutoScrollHandler.postDelayed(this, 25); // Continue scrolling
            }
        }
    };

    public DragSelectTouchListener(Context context, OnDragSelectListener listener) {
        this.mListener = listener;
        this.mScrollAmount = 0;
        this.mAutoScrollDistance = (int) (context.getResources().getDisplayMetrics().density * 56); // 56dp trigger area
        this.mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
				@Override
				public void onLongPress(MotionEvent e) {
					if (!mIsDragging) {
						startDragSelection(e);
					}
				}
			});
    }

    private void startDragSelection(MotionEvent e) {
        View view = mRecyclerView.findChildViewUnder(e.getX(), e.getY());
        if (view != null) {
            int position = mRecyclerView.getChildAdapterPosition(view);
            if (position != RecyclerView.NO_POSITION) {
                mIsDragging = true;
                mStart = position;
                mEnd = position;
                mListener.onSelectChange(mStart, mEnd, true);
                // --- ADD THIS LINE ---
                // Tell the RecyclerView to not intercept touch events while we are dragging
                mRecyclerView.requestDisallowInterceptTouchEvent(true);
            }
        }
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        if (mRecyclerView == null) {
            mRecyclerView = rv;
        }
        mGestureDetector.onTouchEvent(e);

        if (e.getAction() == MotionEvent.ACTION_UP && mIsDragging) {
            mIsDragging = false;
            stopAutoScroll();
            // --- ADD THIS LINE ---
            // Allow the RecyclerView to intercept touch events again
            mRecyclerView.requestDisallowInterceptTouchEvent(false);
            return true; // Consume the up event to stop further processing
        }
        return mIsDragging;
    }

    @Override
    public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_MOVE && mIsDragging) {
            handleDrag(e);
        } else if (e.getAction() == MotionEvent.ACTION_UP) {
            mIsDragging = false;
            stopAutoScroll();
            // --- ADD THIS LINE ---
            // Allow the RecyclerView to intercept touch events again
            mRecyclerView.requestDisallowInterceptTouchEvent(false);
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // Not used
    }

    private void handleDrag(MotionEvent e) {
        // Handle auto-scrolling
        if (e.getY() < mAutoScrollDistance) {
            startAutoScroll(-20); // Scroll up
        } else if (e.getY() > mRecyclerView.getHeight() - mAutoScrollDistance) {
            startAutoScroll(20); // Scroll down
        } else {
            stopAutoScroll();
        }

        // Handle selection
        View view = mRecyclerView.findChildViewUnder(e.getX(), e.getY());
        if (view != null) {
            int position = mRecyclerView.getChildAdapterPosition(view);
            if (position != RecyclerView.NO_POSITION && position != mEnd) {
                int oldEnd = mEnd;
                mEnd = position;
                notifyListener(oldEnd);
            }
        }
    }

    private void notifyListener(int oldEnd) {
        if (mListener == null) return;

        int newMin = Math.min(mStart, mEnd);
        int newMax = Math.max(mStart, mEnd);
        int oldMin = Math.min(mStart, oldEnd);
        int oldMax = Math.max(mStart, oldEnd);

        // Notify items that are now selected
        for (int i = newMin; i <= newMax; i++) {
            if (i < oldMin || i > oldMax) {
                mListener.onSelectChange(i, i, true);
            }
        }

        // Notify items that are now unselected
        for (int i = oldMin; i <= oldMax; i++) {
            if (i < newMin || i > newMax) {
                mListener.onSelectChange(i, i, false);
            }
        }
    }

    private void startAutoScroll(int pixels) {
        this.mScrollAmount = pixels; // Set the scroll speed and direction
        if (!mIsAutoScrolling) {
            mIsAutoScrolling = true;
            mAutoScrollHandler.post(mAutoScrollRunnable); // Start the scrolling loop
        }
    }


    private void stopAutoScroll() {
        if (mIsAutoScrolling) {
            mIsAutoScrolling = false;
            mAutoScrollHandler.removeCallbacks(mAutoScrollRunnable);
            this.mScrollAmount = 0;
        }
    }
}


