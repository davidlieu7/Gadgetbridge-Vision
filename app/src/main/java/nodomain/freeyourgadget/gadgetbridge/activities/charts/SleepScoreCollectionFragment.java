package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.os.Bundle;

import androidx.viewpager2.adapter.FragmentStateAdapter;

import nodomain.freeyourgadget.gadgetbridge.adapter.SleepScoreFragmentAdapter;

public class SleepScoreCollectionFragment extends AbstractCollectionFragment {
    public SleepScoreCollectionFragment() {

    }

    public static SleepScoreCollectionFragment newInstance(final boolean allowSwipe) {
        final SleepScoreCollectionFragment fragment = new SleepScoreCollectionFragment();
        final Bundle args = new Bundle();
        args.putBoolean(ARG_ALLOW_SWIPE, allowSwipe);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public FragmentStateAdapter getFragmentAdapter() {
        return new SleepScoreFragmentAdapter(this);
    }
}
