package org.tecna.easypets.translation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Server-side translation manager for EasyPets.
 * Loads and processes lang files on the server since this is a server-side mod.
 * Supports multiple languages with fallback to English.
 */
public class TranslationManager {
    private static final TranslationManager INSTANCE = new TranslationManager();
    private final Map<String, String> translations = new HashMap<>();
    private final Map<String, String> fallbackTranslations = new HashMap<>();
    private final Map<String, Boolean> loadedTranslations = new HashMap<>();
    private String currentLanguage = "en_us";
    private boolean loaded = false;

    private TranslationManager() {
        // Don't load here - let Config initialize first
    }

    public static TranslationManager getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize translations with a specific language.
     * Called after Config is loaded.
     */
    public void initialize(String language) {
        this.currentLanguage = language;
        
        // Always load English as fallback first
        loadLanguageFile("en_us", fallbackTranslations);
        
        // Load requested language
        if (!language.equals("en_us")) {
            loadLanguageFile(language, translations);
        } else {
            translations.putAll(fallbackTranslations);
        }
        
        loaded = true;
        System.out.println("[EasyPets] Loaded " + translations.size() + " translations for language: " + language);
    }
    
    /**
     * Load a specific language file into the given map
     */
    private void loadLanguageFile(String language, Map<String, String> targetMap) {
        try {
            String path = "/assets/easypets/lang/" + language + ".json";
            var inputStream = getClass().getResourceAsStream(path);
            
            if (inputStream == null) {
                if (!language.equals("en_us")) {
                    System.out.println("[EasyPets] Language file not found: " + language + ".json, using English");
                } else {
                    System.err.println("[EasyPets] CRITICAL: en_us.json not found!");
                }
                return;
            }

            Gson gson = new Gson();
            JsonObject json = gson.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), JsonObject.class);

            json.entrySet().forEach(entry -> 
                targetMap.put(entry.getKey(), entry.getValue().getAsString())
            );
            
            // Track that this language was successfully loaded
            loadedTranslations.put(language, true);
            
            inputStream.close();

        } catch (Exception e) {
            System.err.println("[EasyPets] Error loading language file " + language + ": " + e.getMessage());
            e.printStackTrace();
            loadedTranslations.put(language, false);
        }
    }
    
    /**
     * Reload translations with a new language
     */
    public void reloadLanguage(String language) {
        translations.clear();
        fallbackTranslations.clear();
        loaded = false;
        initialize(language);
    }

    /**
     * Translate a key with arguments, using String.format style formatting.
     * Falls back to English if key not found in current language.
     */
    public String translate(String key, Object... args) {
        if (!loaded) {
            return key; // Fallback to key if loading failed
        }

        // Try current language first, then fallback to English, then use key itself
        String template = translations.get(key);
        if (template == null) {
            template = fallbackTranslations.getOrDefault(key, key);
        }
        
        if (args.length == 0) {
            return template;
        }

        try {
            return String.format(template, args);
        } catch (Exception e) {
            System.err.println("[EasyPets] Error formatting translation '" + key + "': " + e.getMessage());
            return template;
        }
    }

    /**
     * Create a formatted Text with color code and translation
     */
    public Text text(String color, String key, Object... args) {
        return Text.literal(color + translate(key, args));
    }

    /**
     * Append translated text to existing Text
     */
    public Text append(String color, String key, Object... args) {
        return Text.literal(color).append(Text.literal(translate(key, args)));
    }

    /**
     * Check if translations are loaded
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Get translation count for debugging
     */
    public int getTranslationCount() {
        return translations.size();
    }
    
    /**
     * Get current language code
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }
    
    /**
     * Get available language codes by scanning the resources directory
     */
    public List<String> getAvailableLanguages() {
        Set<String> languages = new HashSet<>();
        
        try {
            // Try to read from the resources using class loader
            var langDir = getClass().getResource("/assets/easypets/lang/");
            
            if (langDir != null) {
                // If we're running from a JAR, we need to list resources differently
                if (langDir.getProtocol().equals("jar")) {
                    // Running from JAR - scan JAR entries
                    var jarConnection = langDir.openConnection();
                    if (jarConnection instanceof java.net.JarURLConnection jarConn) {
                        var jarFile = jarConn.getJarFile();
                        var entries = jarFile.entries();
                        
                        while (entries.hasMoreElements()) {
                            var entry = entries.nextElement();
                            String name = entry.getName();
                            
                            if (name.startsWith("assets/easypets/lang/") && name.endsWith(".json")) {
                                String langCode = name.substring(name.lastIndexOf('/') + 1, name.lastIndexOf('.'));
                                languages.add(langCode);
                            }
                        }
                    }
                } else {
                    // Running from IDE/file system - list directory
                    try {
                        java.nio.file.Path path = java.nio.file.Paths.get(langDir.toURI());
                        try (var stream = java.nio.file.Files.list(path)) {
                            stream.filter(p -> p.toString().endsWith(".json"))
                                  .forEach(p -> {
                                      String fileName = p.getFileName().toString();
                                      String langCode = fileName.substring(0, fileName.lastIndexOf('.'));
                                      languages.add(langCode);
                                  });
                        }
                    } catch (Exception e) {
                        System.err.println("[EasyPets] Error listing language files: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[EasyPets] Error detecting available languages: " + e.getMessage());
        }
        
        // Always ensure en_us is available as fallback
        if (languages.isEmpty()) {
            languages.add("en_us");
            if (loadedTranslations.containsKey("es_es")) {
                languages.add("es_es");
            }
        }
        
        return languages.stream().sorted().collect(Collectors.toList());
    }
}
