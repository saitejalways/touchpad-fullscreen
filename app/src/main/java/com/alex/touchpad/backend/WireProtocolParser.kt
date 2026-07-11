package com.alex.touchpad.backend

import com.alex.touchpad.input.MouseButton

object WireProtocolParser {
    fun parse(line: String): WireCommand? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.isEmpty()) {
            return null
        }

        return when (tokens[0]) {
            "MOVE_REL" -> parseMove(tokens)
            "BUTTON_DOWN" -> parseButtonDown(tokens)
            "BUTTON_UP" -> parseButtonUp(tokens)
            "CLICK" -> parseClick(tokens)
            "SCROLL" -> parseScroll(tokens)
            "TOUCH_CONTACT" -> parseTouchContact(tokens)
            "TOUCH_DELTA" -> parseTouchDelta(tokens)
            "PING" -> parsePing(tokens)
            "PAIR" -> parsePair(tokens)
            else -> null
        }
    }

    private fun parseMove(tokens: List<String>): WireCommand? {
        if (tokens.size < 5) return null
        return WireCommand.MoveRel(
            dx = tokens[1].toIntOrNull() ?: return null,
            dy = tokens[2].toIntOrNull() ?: return null,
            seq = tokens[3].toLongOrNull() ?: return null,
            ts = tokens[4].toLongOrNull() ?: return null,
        )
    }

    private fun parseButtonDown(tokens: List<String>): WireCommand? {
        if (tokens.size < 4) return null
        return WireCommand.ButtonDown(
            button = parseButton(tokens[1]) ?: return null,
            seq = tokens[2].toLongOrNull() ?: return null,
            ts = tokens[3].toLongOrNull() ?: return null,
        )
    }

    private fun parseButtonUp(tokens: List<String>): WireCommand? {
        if (tokens.size < 4) return null
        return WireCommand.ButtonUp(
            button = parseButton(tokens[1]) ?: return null,
            seq = tokens[2].toLongOrNull() ?: return null,
            ts = tokens[3].toLongOrNull() ?: return null,
        )
    }

    private fun parseClick(tokens: List<String>): WireCommand? {
        if (tokens.size < 4) return null
        return WireCommand.Click(
            button = parseButton(tokens[1]) ?: return null,
            seq = tokens[2].toLongOrNull() ?: return null,
            ts = tokens[3].toLongOrNull() ?: return null,
        )
    }

    private fun parseScroll(tokens: List<String>): WireCommand? {
        if (tokens.size < 5) return null
        return WireCommand.ScrollWheel(
            vWheel = tokens[1].toIntOrNull() ?: return null,
            hWheel = tokens[2].toIntOrNull() ?: return null,
            seq = tokens[3].toLongOrNull() ?: return null,
            ts = tokens[4].toLongOrNull() ?: return null,
        )
    }

    private fun parsePing(tokens: List<String>): WireCommand? {
        if (tokens.size < 3) return null
        return WireCommand.Ping(
            seq = tokens[1].toLongOrNull() ?: return null,
            ts = tokens[2].toLongOrNull() ?: return null,
        )
    }

    private fun parseTouchContact(tokens: List<String>): WireCommand? {
        if (tokens.size < 4) return null
        return WireCommand.TouchContact(
            active = when (tokens[1]) {
                "1", "true", "TRUE" -> true
                "0", "false", "FALSE" -> false
                else -> return null
            },
            seq = tokens[2].toLongOrNull() ?: return null,
            ts = tokens[3].toLongOrNull() ?: return null,
        )
    }

    private fun parseTouchDelta(tokens: List<String>): WireCommand? {
        if (tokens.size < 5) return null
        return WireCommand.TouchDelta(
            dx = tokens[1].toIntOrNull() ?: return null,
            dy = tokens[2].toIntOrNull() ?: return null,
            seq = tokens[3].toLongOrNull() ?: return null,
            ts = tokens[4].toLongOrNull() ?: return null,
        )
    }

    private fun parsePair(tokens: List<String>): WireCommand? {
        if (tokens.size < 6) return null
        return WireCommand.Pair(
            host = tokens[1],
            port = tokens[2].toIntOrNull() ?: return null,
            code = tokens[3],
            seq = tokens[4].toLongOrNull() ?: return null,
            ts = tokens[5].toLongOrNull() ?: return null,
        )
    }

    private fun parseButton(raw: String): MouseButton? {
        return when (raw.uppercase()) {
            MouseButton.LEFT.name -> MouseButton.LEFT
            MouseButton.RIGHT.name -> MouseButton.RIGHT
            MouseButton.MIDDLE.name -> MouseButton.MIDDLE
            else -> null
        }
    }
}
