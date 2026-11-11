package app.clinicreminders.api;

import java.util.Collections;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import app.clinicreminders.common.Language;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every dashboard text in one language, from the same {@code messages_*.properties} the pages use,
 * so React and the server never disagree on wording. Patterns are as written: React fills
 * {@code {0}}, {@code {1}}... and, as Java's MessageFormat does for texts with arguments, turns
 * {@code ''} into an apostrophe.
 */
@RestController
class MessagesApi {

    private final Map<Language, Map<String, String>> cache = new ConcurrentHashMap<>();

    @GetMapping("/api/messages")
    ResponseEntity<Map<String, String>> messages(@RequestParam(defaultValue = "ar") String lang) {
        Language language = "en".equals(lang) ? Language.EN : Language.AR;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(cache.computeIfAbsent(language, MessagesApi::load));
    }

    /** {@code messages_ar} or {@code messages_en}, over {@code messages}; never the server's own locale. */
    private static Map<String, String> load(Language language) {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", language.locale(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        Map<String, String> texts = new TreeMap<>();
        for (String key : Collections.list(bundle.getKeys())) {
            texts.put(key, bundle.getString(key));
        }
        return Collections.unmodifiableMap(texts);
    }
}
