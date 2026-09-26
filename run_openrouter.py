import os
import subprocess
import sys
from pathlib import Path


def _interpreter() -> Path:
    candidates = [
        Path(__file__).with_name(".venv") / ("Scripts/python.exe" if os.name == "nt" else "bin/python"),
        Path(__file__).with_name("venv") / ("Scripts/python.exe" if os.name == "nt" else "bin/python"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return Path(sys.executable)


if __name__ == "__main__":
    main = Path(__file__).with_name("main.py")
    raise SystemExit(subprocess.run([str(_interpreter()), str(main)]).returncode)
