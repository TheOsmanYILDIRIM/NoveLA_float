package my.noveldokusha.text_to_speech.ema

import org.junit.Assert.assertEquals
import org.junit.Test

class EmaTurkishNormalizerTest {
    @Test fun integerAndZeroPrefixedCode() {
        assertEquals("beş kişi geldi.", EmaTurkishNormalizer.normalize("5 kişi geldi."))
        assertEquals("Kod: sıfır sıfır sıfır dört iki", EmaTurkishNormalizer.normalize("Kod: 00042"))
    }

    @Test fun percentageAndDecimalMeasurement() {
        assertEquals("yüzde on beş indirim", EmaTurkishNormalizer.normalize("%15 indirim"))
        assertEquals("on iki virgül beş kilogram un", EmaTurkishNormalizer.normalize("12,5 kg un"))
        assertEquals("üç kilometre", EmaTurkishNormalizer.normalize("3 km"))
    }

    @Test fun datesClocksAndMoney() {
        assertEquals("dokuz otuzda", EmaTurkishNormalizer.normalize("09:30da"))
        assertEquals("dört kasım iki bin yirmi altı",
            EmaTurkishNormalizer.normalize("04.11.2026"))
        assertEquals("yirmi beş Türk lirası", EmaTurkishNormalizer.normalize("25 TL"))
        assertEquals("yüz Türk lirası", EmaTurkishNormalizer.normalize("₺100"))
    }

    @Test fun abbreviationsAndNames() {
        assertEquals("doktor Ayşe geldi.", EmaTurkishNormalizer.normalize("Dr. Ayşe geldi."))
        assertEquals("te re te haber", EmaTurkishNormalizer.normalize("TRT haber"))
        assertEquals("Hawk'ın bir becerisi var.", EmaTurkishNormalizer.normalize("Hawk'ın bir becerisi var."))
    }

    @Test fun novelTitleKeepsMeaningAndBookFormattingIsNotTouched() {
        val input = "Bölüm 742 Alınan Ödeme"
        assertEquals("Bölüm yedi yüz kırk iki Alınan Ödeme",
            EmaTurkishNormalizer.normalize(input))
        assertEquals("Bölüm 742 Alınan Ödeme", input)
    }
}
