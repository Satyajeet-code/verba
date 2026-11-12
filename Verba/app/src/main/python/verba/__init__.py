"""Verba backend.

Runs inside the APK through Chaquopy. The Kotlin side talks to it only through
``verba.api``; every function there takes and returns plain strings / numbers
(JSON for structured data) so the bridge stays simple and testable on a desktop.
"""

__version__ = "1.0.0"
