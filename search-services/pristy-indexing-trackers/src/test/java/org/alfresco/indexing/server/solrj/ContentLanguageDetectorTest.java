/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Pristy software, developed by Jeci SARL.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.indexing.server.solrj;

import static org.junit.Assert.assertEquals;

import java.util.Optional;

import org.junit.Test;

public class ContentLanguageDetectorTest
{
    private final ContentLanguageDetector detector = ContentLanguageDetector.shared();

    @Test
    public void aFrenchMinuteIsDetectedAsFrench()
    {
        assertEquals(Optional.of("fr"), detector.detect(
                "Proc\u00e8s-verbal de la s\u00e9ance du conseil municipal du 12 octobre 2026. Le maire ouvre la "
                + "s\u00e9ance \u00e0 18 heures. Ordre du jour : approbation du budget primitif, attribution des "
                + "subventions aux associations. Apr\u00e8s en avoir d\u00e9lib\u00e9r\u00e9, le conseil municipal adopte "
                + "\u00e0 l'unanimit\u00e9."));
    }

    @Test
    public void anEnglishMinuteIsDetectedAsEnglish()
    {
        assertEquals(Optional.of("en"), detector.detect(
                "Minutes of the board meeting held on 12 October 2026. The chair opened the meeting at 6 pm. "
                + "Agenda: approval of the budget, grants to associations, road works. After deliberation, "
                + "the board unanimously adopted the resolution."));
    }

    @Test
    public void aGermanSentenceIsDetectedAsGerman()
    {
        assertEquals(Optional.of("de"), detector.detect(
                "Die Stadtverwaltung hat beschlossen, die neue Software fuer die Verwaltung der Dokumente "
                + "einzusetzen und die Mitarbeiter zu schulen."));
    }

    @Test
    public void digitsAloneDetectNothing()
    {
        assertEquals(Optional.empty(), detector.detect("1234 5678 9012 3456"));
    }

    @Test
    public void aShortAmbiguousTitleDetectsNothing()
    {
        assertEquals(Optional.empty(), detector.detect("Avis d'imposition"));
    }

    @Test
    public void aTextMixingTwoLanguagesDetectsNothing()
    {
        assertEquals(Optional.empty(), detector.detect(
                "Le contrat est sign\u00e9. The contract is signed. Le contrat est sign\u00e9. The contract is signed."));
    }

    @Test
    public void aBlankTextDetectsNothing()
    {
        assertEquals(Optional.empty(), detector.detect("  "));
        assertEquals(Optional.empty(), detector.detect(null));
    }

    @Test
    public void onlyTheBeginningOfALongTextIsRead()
    {
        String french = "Procès-verbal de la séance. Le maire ouvre la séance à 18 heures. "
                + "Après en avoir délibéré, le conseil adopte à l'unanimité. ";
        String english = "Minutes of the board meeting. The chair opened the meeting at 6 pm. "
                + "After deliberation, the board unanimously adopted the resolution. ";
        String text = french.repeat(ContentLanguageDetector.SAMPLE_LENGTH / french.length() + 1) + english.repeat(1000);

        assertEquals(Optional.of("fr"), detector.detect(text));
    }
}
