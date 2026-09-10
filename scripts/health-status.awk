# Read decimal bytes from od -An -v -tu1, then validate UTF-8 and complete JSON.
# Numeric input preserves NUL even on awk implementations that truncate it.
# Never print input: health details can contain sensitive information.
function byte(code) {
    if (remaining) {
        if (code < lower || code > upper) invalid()
        remaining--
        lower = 128
        upper = 191
    } else if (code >= 128) {
        lower = 128
        upper = 191
        if (code >= 194 && code <= 223) remaining = 1
        else if (code >= 224 && code <= 239) {
            remaining = 2
            if (code == 224) lower = 160 # No overlong encodings.
            if (code == 237) upper = 159 # No UTF-16 surrogates.
        } else if (code >= 240 && code <= 244) {
            remaining = 3
            if (code == 240) lower = 144 # No overlong encodings.
            if (code == 244) upper = 143 # Maximum is U+10FFFF.
        } else invalid()
    } else if (code == 0) invalid() # Raw NUL is never legal JSON.
    document = document sprintf("%c", code)
}
function whitespace() {
    while (substr(document, position, 1) ~ /^[ \t\r\n]$/) position++
}
function invalid() { failed = 1; exit 1 }
function string_value(    result, character, escape, hex, code, i, digit) {
    if (substr(document, position++, 1) != "\"") invalid()
    result = ""
    while (position <= length(document)) {
        character = substr(document, position++, 1)
        if (character == "\"") return result
        # JSON forbids U+0000 through U+001F, but permits DEL (U+007F).
        if (character ~ /[[:cntrl:]]/ && character != sprintf("%c", 127)) invalid()
        if (character == "\\") {
            escape = substr(document, position++, 1)
            if (escape == "u") {
                hex = substr(document, position, 4)
                if (length(hex) != 4 || hex ~ /[^0-9a-fA-F]/) invalid()
                position += 4
                code = 0
                for (i = 1; i <= 4; i++) {
                    digit = index("0123456789abcdef", tolower(substr(hex, i, 1))) - 1
                    code = code * 16 + digit
                }
                # Only ASCII can form the key/status we compare. Other code
                # units remain a nonmatching marker; their syntax is validated.
                character = (code >= 32 && code <= 126) ? sprintf("%c", code) : "?"
            } else if (escape == "\"" || escape == "\\" || escape == "/") {
                character = escape
            } else if (escape ~ /^[bfnrt]$/) {
                character = "?"
            } else invalid()
        }
        result = result character
    }
    invalid()
}
function value(depth,    character, key, decoded, separator, token) {
    # Bound recursion for untrusted/malformed health responses.
    if (depth > 128) invalid()
    whitespace()
    character = substr(document, position, 1)
    if (character == "{") {
        position++
        whitespace()
        if (substr(document, position, 1) == "}") { position++; return }
        while (1) {
            whitespace()
            key = string_value()
            whitespace()
            if (substr(document, position++, 1) != ":") invalid()
            whitespace()
            if (depth == 0 && key == "status") {
                if (++status_count != 1) invalid()
                decoded = string_value()
                status_up = (decoded == "UP")
            } else value(depth + 1)
            whitespace()
            separator = substr(document, position++, 1)
            if (separator == "}") return
            if (separator != ",") invalid()
        }
    }
    if (character == "[") {
        position++
        whitespace()
        if (substr(document, position, 1) == "]") { position++; return }
        while (1) {
            value(depth + 1)
            whitespace()
            separator = substr(document, position++, 1)
            if (separator == "]") return
            if (separator != ",") invalid()
        }
    }
    if (character == "\"") { string_value(); return }
    token = substr(document, position)
    if (match(token, /^(true|false|null)/) ||
        match(token, /^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?/)) {
        position += RLENGTH
        return
    }
    invalid()
}
{ for (field = 1; field <= NF; field++) byte($field + 0) }
END {
    if (failed || remaining) exit 1
    position = 1
    whitespace()
    if (substr(document, position, 1) != "{") invalid()
    value(0)
    whitespace()
    if (position <= length(document) || status_count != 1 || !status_up) exit 1
}
