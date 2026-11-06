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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import opennlp.tools.langdetect.Language;
import opennlp.tools.langdetect.LanguageDetectorME;
import opennlp.tools.langdetect.LanguageDetectorModel;

/**
 * Detects the language of extracted document text with the OpenNLP language detection model,
 * answering only when one language clearly outranks the others.
 */
public final class ContentLanguageDetector
{
    /** Number of leading characters of a text the detection reads. */
    public static final int SAMPLE_LENGTH = 10_000;

    /** Factor by which the best language must outrank the second one to be trusted. */
    public static final double MIN_MARGIN = 1.5;

    private static final String MODEL_RESOURCE = "/langdetect-183.bin";

    private static final Map<String, String> ISO3_TO_ISO2 = new HashMap<>();
    static
    {
        for (String language : Locale.getISOLanguages())
        {
            ISO3_TO_ISO2.put(new Locale(language).getISO3Language(), language);
        }
    }

    private final LanguageDetectorModel model;

    private ContentLanguageDetector(LanguageDetectorModel model)
    {
        this.model = model;
    }

    /**
     * Returns the detector shared by every core, loading the model on first use.
     *
     * @return the shared detector
     */
    public static ContentLanguageDetector shared()
    {
        return Holder.INSTANCE;
    }

    /**
     * Detects the language of a text.
     *
     * @param text the extracted text, only its first {@link #SAMPLE_LENGTH} characters are read
     * @return the ISO 639-1 language code, or empty when the text is blank or no language
     *         outranks the second one by {@link #MIN_MARGIN}
     */
    public Optional<String> detect(String text)
    {
        if (text == null || text.isBlank())
        {
            return Optional.empty();
        }
        String sample = text.length() > SAMPLE_LENGTH ? text.substring(0, SAMPLE_LENGTH) : text;
        Language[] languages = new LanguageDetectorME(model).predictLanguages(sample);
        if (languages.length < 2 || languages[0].getConfidence() < languages[1].getConfidence() * MIN_MARGIN)
        {
            return Optional.empty();
        }
        return Optional.ofNullable(ISO3_TO_ISO2.get(languages[0].getLang()));
    }

    private static final class Holder
    {
        private static final ContentLanguageDetector INSTANCE = new ContentLanguageDetector(load());

        private static LanguageDetectorModel load()
        {
            try (InputStream in = ContentLanguageDetector.class.getResourceAsStream(MODEL_RESOURCE))
            {
                if (in == null)
                {
                    throw new IllegalStateException("Language detection model not found: " + MODEL_RESOURCE);
                }
                return new LanguageDetectorModel(in);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException("Cannot load the language detection model " + MODEL_RESOURCE, e);
            }
        }
    }
}
