package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import androidx.annotation.NonNull;

import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.formatter.IValueFormatter;
import com.github.mikephil.charting.utils.ViewPortHandler;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;

public class BarChartStackedTimeValueFormatter implements IValueFormatter {
    private final int ignoreLast;

    public BarChartStackedTimeValueFormatter(final int ignoreLast) {
        this.ignoreLast = ignoreLast;
    }

    private int getLastNonZeroIndex(final List<Float> values) {
        int last = 0;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != 0) {
                last = i;
            }
        }
        return last;
    }

    @NonNull
    @Override
    public String getFormattedValue(final float value, @NonNull final Entry<?> entry, final int dataSetIndex, @NonNull final ViewPortHandler viewPortHandler) {
        return DateTimeUtils.minutesToHHMM((int) value);
    }

    @NonNull
    @Override
    public String getStackedFormattedValue(final float value, final int stackIndex, @NonNull final Entry<?> entry, final int dataSetIndex, @NonNull final ViewPortHandler viewPortHandler) {
        final List<Float> values = ((BarEntry<?>) entry).getStackValues();
        if (values == null || stackIndex != getLastNonZeroIndex(values)) {
            return "";
        }
        float sum = 0;
        for (int i = 0; i < values.size() - ignoreLast; i++) {
            sum += values.get(i);
        }
        return DateTimeUtils.minutesToHHMM((int) sum);
    }
}
