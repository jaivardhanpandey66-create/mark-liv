"""Clipboard access that works on both X11 and Wayland.

pyperclip drives the clipboard by shelling out to whichever helper it finds
first — xclip, then xsel, then wl-copy. On a GNOME Wayland session xclip is
present and *appears* to work, but its selection-owner process never releases
the pipe, so pyperclip's Popen.communicate() blocks forever and the assistant
appears unable to copy anything at all.

So the Wayland helper is tried first when the session is Wayland, and pyperclip
stays as the fallback. Every helper is bounded by a timeout: a clipboard is not
worth hanging a voice command over, and a failed copy should surface as an
error rather than a stuck request.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

TIMEOUT = 5


def _have(name: str) -> bool:
    return shutil.which(name) is not None


def _wayland() -> bool:
    return bool(os.environ.get("WAYLAND_DISPLAY"))


def _run(argv: list[str], stdin: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        argv,
        input=stdin.encode("utf-8") if stdin is not None else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=TIMEOUT,
    )


def copy(text: str) -> bool:
    """Put `text` on the clipboard. Returns True on success."""
    data = "" if text is None else str(text)

    if _wayland() and _have("wl-copy"):
        try:
            _run(["wl-copy"], stdin=data)
            return True
        except (subprocess.SubprocessError, OSError):
            pass          # wl-copy missing/stalled — try the next mechanism

    if _have("xclip"):
        try:
            proc = subprocess.Popen(
                ["xclip", "-selection", "clipboard"],
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            proc.communicate(input=data.encode("utf-8"), timeout=TIMEOUT)
            return proc.returncode == 0
        except (subprocess.SubprocessError, OSError):
            pass

    if _have("xsel"):
        try:
            _run(["xsel", "--clipboard", "--input"], stdin=data)
            return True
        except (subprocess.SubprocessError, OSError):
            pass

    try:
        import pyperclip
        pyperclip.copy(data)
        return True
    except Exception:
        return False


def paste() -> str:
    """Read the clipboard. Returns "" when it cannot be read."""
    if _wayland() and _have("wl-paste"):
        try:
            return _run(["wl-paste", "--no-newline"]).stdout.decode("utf-8", "replace")
        except (subprocess.SubprocessError, OSError):
            pass

    for argv in (
        ["xclip", "-selection", "clipboard", "-o"],
        ["xsel", "--clipboard", "--output"],
        ["wl-paste", "--no-newline"],
    ):
        if not _have(argv[0]):
            continue
        try:
            return _run(argv).stdout.decode("utf-8", "replace")
        except (subprocess.SubprocessError, OSError):
            continue

    try:
        import pyperclip
        return str(pyperclip.paste())
    except Exception:
        return ""
