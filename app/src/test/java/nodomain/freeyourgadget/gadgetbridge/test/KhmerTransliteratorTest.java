package nodomain.freeyourgadget.gadgetbridge.test;

import junit.framework.TestCase;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.util.language.LanguageUtils;
import nodomain.freeyourgadget.gadgetbridge.util.language.Transliterator;
import nodomain.freeyourgadget.gadgetbridge.util.language.impl.KhmerTransliterator;

public class KhmerTransliteratorTest extends TestCase {
    private final Transliterator transliterator = new KhmerTransliterator();

    @Test
    public void testRegistered() {
        Assert.assertNotNull(LanguageUtils.getTransliterator("khmer"));
    }

    @Test
    public void testWords() {
        final Map<String, String> words = new LinkedHashMap<String, String>() {{
            put("ក", "kâ");                 // inherent vowel on a single consonant
            put("កម្ពុជា", "kâmpŭcha");      // subscript consonant
            put("ភ្នំពេញ", "phnumpénh");      // no inherent vowel on the final consonant
            put("សៀមរាប", "siĕmrab");        // final consonant before a new syllable
            put("បាត់ដំបង", "batdâmbâng");    // sign without sound, consonant-vowel-consonant syllable
            put("អរគុណ", "’ârkŭn");
            put("ខ្ញុំ", "khnhom");           // sonorant subscript keeps the series of the base
            put("ថ្ងៃ", "thngai");
            put("ម៉ោង", "maông");            // series switch sign
            put("ឧបករណ៍", "ŏbâkârôn");       // independent vowel
        }};
        for (final Map.Entry<String, String> e : words.entrySet()) {
            Assert.assertEquals("Transliteration failed for " + e.getKey(), e.getValue(), transliterator.transliterate(e.getKey()));
        }
    }

    @Test
    public void testDigitsAndPunctuation() {
        Assert.assertEquals("chhnăm 2026", transliterator.transliterate("ឆ្នាំ ២០២៦"));
        // zero-width spaces between words become spaces, ៕ becomes a full stop
        Assert.assertEquals("paky ti muŏy.", transliterator.transliterate("ពាក្យ​ទី​មួយ៕"));
    }

    @Test
    public void testMixedStrings() {
        Assert.assertEquals("Hello suŏstei!", transliterator.transliterate("Hello សួស្តី!"));
        Assert.assertEquals("Plain text stays the same", transliterator.transliterate("Plain text stays the same"));
    }

    @Test
    public void testWordBoundaries() {
        // a word ending in a vowel must not drop the inherent vowel of the next word
        Assert.assertEquals("ka kâ", transliterator.transliterate("កា ក"));
        Assert.assertEquals("ka kâ", transliterator.transliterate("កា​ក"));
        Assert.assertEquals("ka.kâ", transliterator.transliterate("កា។ក"));
    }

    @Test
    public void testPrivateUseCharactersAreKept() {
        final String text = "xyz ា";
        Assert.assertEquals("xyz a", transliterator.transliterate(text));
    }

    @Test
    public void testEmpty() {
        Assert.assertEquals("", transliterator.transliterate(""));
        Assert.assertNull(transliterator.transliterate(null));
    }
}
