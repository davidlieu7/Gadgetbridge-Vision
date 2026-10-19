package nodomain.freeyourgadget.gadgetbridge.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import nodomain.freeyourgadget.gadgetbridge.activities.charts.SleepScoreDailyFragment;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.SleepScorePeriodFragment;

public class SleepScoreFragmentAdapter extends NestedFragmentAdapter {
    public SleepScoreFragmentAdapter(Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1:
                return SleepScorePeriodFragment.newInstance(7);
            case 2:
                return SleepScorePeriodFragment.newInstance(30);
        }
        return new SleepScoreDailyFragment();
    }
}
