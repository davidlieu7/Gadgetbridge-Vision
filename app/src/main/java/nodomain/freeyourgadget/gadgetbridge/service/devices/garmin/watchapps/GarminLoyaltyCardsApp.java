package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.watchapps;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.capabilities.loyaltycards.LoyaltyCard;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.GarminSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.communicator.GarminWatchAppCommunicator;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.GarminJson;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.GarminJsonException;

/**
 * The custom Loyalty Cards watch app.
 * <p>
 * A header message contains the version of the card list and number of cards. Each card consists of a header with
 * the name, the code text, the color, and the size of the barcode bit matrix. The bits are row by row, in 32-bit numbers with
 * the most significant bit first. The header also contains the first set of numbers, and subsequent data messages
 * contain the remaining numbers, if needed.
 */
public class GarminLoyaltyCardsApp extends GarminWatchApp {
    private static final Logger LOG = LoggerFactory.getLogger(GarminLoyaltyCardsApp.class);

    public static final UUID APP_UUID = UUID.fromString("ea44277e-441e-4d2d-987e-5a5a59643016");

    private static final int MAX_NAME_LENGTH = 24;
    private static final int MAX_CODE_LENGTH = 48;

    private List<JsonObject> messages;

    public GarminLoyaltyCardsApp(final GarminSupport support) {
        super(support, APP_UUID);
    }

    public void onSetLoyaltyCards(final List<LoyaltyCard> cards) {
        messages = encode(cards);
        send();
    }

    @Override
    public void onMessage(final JsonElement message) {
        if (messages == null || !message.isJsonObject()) {
            return;
        }
        final JsonElement watchVersion = message.getAsJsonObject().get("v");
        if (messages.get(0).get("v").equals(watchVersion)) {
            LOG.debug("Watch already has the latest loyalty cards, version {}", watchVersion);
            return;
        }
        send();
    }

    private void send() {
        if (!isAvailable()) {
            LOG.warn("Loyalty cards app is not installed");
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            sendMessage(messages.get(i), i == 0);
        }
    }

    private static List<JsonObject> encode(final List<LoyaltyCard> cards) {
        final List<JsonObject> headers = new ArrayList<>();
        final List<JsonArray> data = new ArrayList<>();
        for (final LoyaltyCard card : cards) {
            final BitMatrix matrix = encodeBarcode(card);
            if (matrix == null) {
                continue;
            }

            final JsonObject header = new JsonObject();
            header.addProperty("name", StringUtils.truncate(card.getName(), MAX_NAME_LENGTH));
            final String value = getBarcodeValue(card);
            if (value.length() <= MAX_CODE_LENGTH) {
                header.addProperty("code", value);
            }
            if (card.getColor() != null) {
                header.addProperty("c", card.getColor() & 0xffffff);
            }
            header.addProperty("w", matrix.getWidth());
            header.addProperty("h", matrix.getHeight());
            headers.add(header);
            data.add(pack(matrix));
        }

        final int version = Objects.hash(headers, data);
        final List<JsonObject> messages = getJsonObjects(headers, version, data);

        LOG.debug("Encoded {} loyalty cards with version {} in {} messages", headers.size(), version, messages.size());

        return messages;
    }

    @NonNull
    private static List<JsonObject> getJsonObjects(final List<JsonObject> headers, final int version, final List<JsonArray> data) {
        final List<JsonObject> messages = new ArrayList<>();
        if (headers.isEmpty()) {
            final JsonObject empty = new JsonObject();
            empty.addProperty("v", version);
            empty.addProperty("n", 0);
            messages.add(empty);
        }
        for (int i = 0; i < headers.size(); i++) {
            final JsonObject header = headers.get(i);
            header.addProperty("v", version);
            header.addProperty("n", headers.size());
            header.addProperty("i", i);

            final JsonArray cardData = data.get(i);
            int offset = fill(header, cardData, 0);
            messages.add(header);
            while (offset < cardData.size()) {
                final JsonObject chunk = new JsonObject();
                chunk.addProperty("i", i);
                chunk.addProperty("o", offset);
                final int next = fill(chunk, cardData, offset);
                if (next == offset) {
                    LOG.error("Data of card {} does not fit in a message", i);
                    break;
                }
                offset = next;
                messages.add(chunk);
            }
        }
        return messages;
    }

    private static int fill(final JsonObject message, final JsonArray data, final int offset) {
        final JsonArray part = new JsonArray();
        message.add("d", part);
        int next = offset;
        while (next < data.size()) {
            part.add(data.get(next));
            if (!fits(message)) {
                part.remove(part.size() - 1);
                break;
            }
            next++;
        }
        return next;
    }

    private static boolean fits(final JsonObject message) {
        try {
            final byte[] blob = GarminJson.encode(message);
            return GarminWatchAppCommunicator.pad(blob).length <= GarminWatchAppCommunicator.MAX_MESSAGE_SIZE;
        } catch (final GarminJsonException e) {
            LOG.error("Failed to encode message", e);
            return false;
        }
    }

    private static String getBarcodeValue(final LoyaltyCard card) {
        return card.getBarcodeId() != null ? card.getBarcodeId() : card.getCardId();
    }

    @Nullable
    private static BitMatrix encodeBarcode(final LoyaltyCard card) {
        if (card.getBarcodeFormat() == null) {
            LOG.warn("Card {} has no barcode format", card);
            return null;
        }

        try {
            final Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.CHARACTER_SET, card.getBarcodeEncoding().name());
            return new MultiFormatWriter().encode(
                getBarcodeValue(card),
                com.google.zxing.BarcodeFormat.valueOf(card.getBarcodeFormat().name()),
                0,
                0,
                hints
            );
        } catch (final WriterException | IllegalArgumentException e) {
            LOG.error("Failed to encode the barcode of card {}", card, e);
            return null;
        }
    }

    private static JsonArray pack(final BitMatrix matrix) {
        final int width = matrix.getWidth();
        final int[] words = new int[(width * matrix.getHeight() + 31) / 32];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < width; x++) {
                if (matrix.get(x, y)) {
                    final int bit = y * width + x;
                    words[bit / 32] |= 1 << (31 - bit % 32);
                }
            }
        }
        final JsonArray array = new JsonArray(words.length);
        for (final int word : words) {
            array.add(word);
        }
        return array;
    }
}
