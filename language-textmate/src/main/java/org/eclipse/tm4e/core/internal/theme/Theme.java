/**
 * Copyright (c) 2015-2017 Angelo ZERR.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 * <p>
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package org.eclipse.tm4e.core.internal.theme;

import static org.eclipse.tm4e.core.internal.utils.MoreCollections.*;
import static org.eclipse.tm4e.core.internal.utils.StringUtils.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.core.internal.grammar.ScopeStack;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

/**
 * TextMate theme.
 *
 * @see <a href=
 * "https://github.com/microsoft/vscode-textmate/blob/e8d1fc5d04b2fc91384c7a895f6c9ff296a38ac8/src/theme.ts#L7">
 * github.com/microsoft/vscode-textmate/blob/main/src/theme.ts</a>
 */
public final class Theme {

    private static final Splitter BY_COMMA_SPLITTER = Splitter.on(',');
    private static final Splitter BY_SPACE_SPLITTER = Splitter.on(' ');

    public static Theme createFromRawTheme(
            @Nullable final IRawTheme source,
            @Nullable final List<String> colorMap) {
        return createFromParsedTheme(parseTheme(source), colorMap);
    }

    public static Theme createFromParsedTheme(
            final List<ParsedThemeRule> source,
            @Nullable final List<String> colorMap) {
        return resolveParsedThemeRules(source, colorMap);
    }

    private final Map<String /* scopeName */, List<ThemeTrieElementRule>> _cachedMatchRoot = new HashMap<>();

    private final ColorMap _colorMap;
    private final StyleAttributes _defaults;
    private final ThemeTrieElement _root;

    public Theme(final ColorMap colorMap, final StyleAttributes defaults, final ThemeTrieElement root) {
        this._colorMap = colorMap;
        this._root = root;
        this._defaults = defaults;
    }

    public List<String> getColorMap() {
        return this._colorMap.getColorMap();
    }

    public StyleAttributes getDefaults() {
        return this._defaults;
    }

    @Nullable
    public StyleAttributes match(@Nullable final ScopeStack scopePath) {
        if (scopePath == null) {
            return this._defaults;
        }
        final var scopeName = scopePath.scopeName;

        final var matchingTrieElements = this._cachedMatchRoot.computeIfAbsent(
                scopeName,
                k -> this._root.match(k));

        final var effectiveRule = findFirstMatching(matchingTrieElements,
                v -> _scopePathMatchesParentScopes(scopePath.parent, v.parentScopes));
        if (effectiveRule == null) {
            return null;
        }

        return new StyleAttributes(
                effectiveRule.fontStyle,
                effectiveRule.foreground,
                effectiveRule.background);
    }

    private boolean _scopePathMatchesParentScopes(@Nullable ScopeStack scopePath,
                                                  @Nullable final List<String> parentScopeNames) {
        if (parentScopeNames == null) {
            return true;
        }

        var index = 0;
        var scopePattern = parentScopeNames.get(index);

        while (scopePath != null) {
            if (_matchesScope(scopePath.scopeName, scopePattern)) {
                index++;
                if (index == parentScopeNames.size()) {
                    return true;
                }
                scopePattern = parentScopeNames.get(index);
            }
            scopePath = scopePath.parent;
        }

        return false;
    }

    private boolean _matchesScope(final String scopeName, final String scopeNamePattern) {
        return scopeNamePattern.equals(scopeName)
                || scopeName.startsWith(scopeNamePattern) && scopeName.charAt(scopeNamePattern.length()) == '.';
    }

    /**
     * Parse a raw theme into rules.
     */
    public static List<ParsedThemeRule> parseTheme(@Nullable final IRawTheme source) {
        if (source == null) {
            return Collections.emptyList();
        }

        final var settings = source.getSettings();
        if (settings == null) {
            return Collections.emptyList();
        }

        final var result = new ArrayList<ParsedThemeRule>();
        int i = -1;
        for (final IRawThemeSetting entry : settings) {
            final var entrySetting = entry.getSetting();
            if (entrySetting == null) {
                continue;
            }

            i++;

            final Object settingScope = entry.getScope();
            List<String> scopes;
            if (settingScope instanceof String) {

                var _scope = (String) settingScope;

                // remove leading commas
                _scope = _scope.replaceAll("^[,]+", "");

                // remove trailing commas
                _scope = _scope.replaceAll("[,]+$", "");

                scopes = BY_COMMA_SPLITTER.splitToList(_scope);
            } else if (settingScope instanceof List) {
                @SuppressWarnings("unchecked") final var settingScopes = (List<String>) settingScope;
                scopes = settingScopes;
            } else {
                scopes = List.of("");
            }

            int fontStyle = FontStyle.NotSet;
            final var settingsFontStyle = entrySetting.getFontStyle();
            if (settingsFontStyle instanceof String) {
                final var style = (String) settingsFontStyle;
                fontStyle = FontStyle.None;

                final var segments = BY_SPACE_SPLITTER.split(style);
                for (final var segment : segments) {
                    switch (segment) {
                        case "italic":
                            fontStyle = fontStyle | FontStyle.Italic;
                            break;
                        case "bold":
                            fontStyle = fontStyle | FontStyle.Bold;
                            break;
                        case "underline":
                            fontStyle = fontStyle | FontStyle.Underline;
                            break;
                        case "strikethrough":
                            fontStyle = fontStyle | FontStyle.Strikethrough;
                            break;
                    }
                }
            }

            String foreground = null;
            final Object settingsForeground = entrySetting.getForeground();
            if (settingsForeground != null && settingsForeground instanceof String
                    && isValidHexColor((String) settingsForeground)) {
                foreground = (String) settingsForeground;
            }

            String background = null;
            final Object settingsBackground = entrySetting.getBackground();
            if (settingsBackground != null && settingsBackground instanceof String
                    && isValidHexColor((String) settingsBackground)) {
                background = (String) settingsBackground;
            }

            for (int j = 0, lenJ = scopes.size(); j < lenJ; j++) {
                final var _scope = scopes.get(j).trim();

                final var segments = BY_SPACE_SPLITTER.splitToList(_scope);

                final var scope = getLastElement(segments);
                List<String> parentScopes = null;
                if (segments.size() > 1) {
                    parentScopes = segments.subList(0, segments.size() - 1);
                    parentScopes = Lists.reverse(parentScopes);
                }

                result.add(new ParsedThemeRule(
                        scope,
                        parentScopes,
                        i,
                        fontStyle,
                        foreground,
                        background));
            }
        }

        return result;
    }

    /**
     * Resolve rules (i.e. inheritance).
     */
    public static Theme resolveParsedThemeRules(final List<ParsedThemeRule> _parsedThemeRules,
                                                @Nullable final List<String> _colorMap) {

        // copy the list since we cannot be sure the given list is mutable
        final var parsedThemeRules = new ArrayList<>(_parsedThemeRules);

        // Sort rules lexicographically, and then by index if necessary
        Collections.sort(parsedThemeRules, (a, b) -> {
            int r = strcmp(a.scope, b.scope);
            if (r != 0) {
                return r;
            }
            r = strArrCmp(a.parentScopes, b.parentScopes);
            if (r != 0) {
                return r;
            }
            return a.index - b.index;
        });

        // Determine defaults
        int defaultFontStyle = FontStyle.None;
        String defaultForeground = "#000000";
        String defaultBackground = "#ffffff";
        while (!parsedThemeRules.isEmpty() && parsedThemeRules.get(0).scope.isEmpty()) {
            final var incomingDefaults = parsedThemeRules.remove(0);
            if (incomingDefaults.fontStyle != FontStyle.NotSet) {
                defaultFontStyle = incomingDefaults.fontStyle;
            }
            if (incomingDefaults.foreground != null) {
                defaultForeground = incomingDefaults.foreground;
            }
            if (incomingDefaults.background != null) {
                defaultBackground = incomingDefaults.background;
            }
        }
        final var colorMap = new ColorMap(_colorMap);
       final var defaults = new StyleAttributes(defaultFontStyle, colorMap.getId(defaultForeground),
                colorMap.getId(defaultBackground));

        final var root = new ThemeTrieElement(new ThemeTrieElementRule(0, null, FontStyle.NotSet, 0, 0),
                Collections.emptyList());
        for (int i = 0, len = parsedThemeRules.size(); i < len; i++) {
            final var rule = parsedThemeRules.get(i);
            root.insert(0, rule.scope, rule.parentScopes, rule.fontStyle, colorMap.getId(rule.foreground),
                    colorMap.getId(rule.background));
        }

        return new Theme(colorMap, defaults, root);
    }

    /**
     * Used to get the color, inherited from the old version of the method
     * @param id index of color
     * @return color string
     */
    public String getColor(int id) {
        return  _colorMap.getColor(id);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + _colorMap.hashCode();
        result = prime * result + _defaults.hashCode();
        result = prime * result + _root.hashCode();
        return result;
    }

    @Override
    public boolean equals(@Nullable final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final Theme other = (Theme) obj;
        return Objects.equals(_colorMap, other._colorMap)
                && Objects.equals(_defaults, other._defaults)
                && Objects.equals(_root, other._root);
    }
}
