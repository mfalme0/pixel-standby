#!/usr/bin/env python3
"""
notify-run — run a command and post the result to an ntfy topic.

Usage:
    notify-run --title "opencode" -- <command and args>

Runs the command, streams its output to the terminal unchanged, records the exit
code and duration, and posts a summary such as:

    opencode finished in 4m12s, exit 0

to the configured ntfy topic. Failures (non-zero exit) are posted as high
priority.

Options:
    --title TITLE         Name shown in the notification (default: basename of
                          the command).
    --server URL          ntfy server (default: https://ntfy.sh, or NTFY_SERVER
                          env var, or config file).
    --topic TOPIC         ntfy topic (default: NTFY_TOPIC env var, or config
                          file).
    --token TOKEN         Bearer token for the ntfy server (default: NTFY_TOKEN
                          env var, or config file).
    --config PATH         Path to a TOML config file (default: ./notify-run.toml
                          or ~/.config/notify-run/notify-run.toml).
    --on-prompt           Post a message and wait for a reply. The reply path is
                          a stub — the message is posted and the tool exits.
    --priority PRIO       Override priority (low, default, high, urgent).
    --tags TAGS           Comma-separated ntfy tags.
    -h, --help            Show this help.

Environment variables:
    NTFY_SERVER, NTFY_TOPIC, NTFY_TOKEN

Config file (TOML):
    server = "https://ntfy.example.com"
    topic = "my-topic"
    token = "secret-token"
"""

from __future__ import annotations

import argparse
import configparser
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

try:
    import tomllib  # Python 3.11+
except ModuleNotFoundError:  # pragma: no cover
    tomllib = None


DEFAULT_SERVER = "https://ntfy.sh"
DEFAULT_TOPIC: Optional[str] = None
DEFAULT_TOKEN: Optional[str] = None

CONFIG_PATHS = [
    Path("notify-run.toml"),
    Path.home() / ".config" / "notify-run" / "notify-run.toml",
]


class Config:
    """Configuration loaded from CLI flags, env vars, and a TOML file."""

    def __init__(
        self,
        server: str = DEFAULT_SERVER,
        topic: Optional[str] = DEFAULT_TOPIC,
        token: Optional[str] = DEFAULT_TOKEN,
    ) -> None:
        self.server = server.rstrip("/")
        self.topic = topic
        self.token = token

    @classmethod
    def load(cls, config_path: Optional[Path] = None) -> "Config":
        server = os.environ.get("NTFY_SERVER", DEFAULT_SERVER)
        topic = os.environ.get("NTFY_TOPIC", DEFAULT_TOPIC)
        token = os.environ.get("NTFY_TOKEN", DEFAULT_TOKEN)

        # TOML config file (lowest priority — env vars override it).
        toml_path = config_path
        if toml_path is None:
            for candidate in CONFIG_PATHS:
                if candidate.is_file():
                    toml_path = candidate
                    break
        if toml_path is not None and tomllib is not None:
            try:
                with open(toml_path, "rb") as f:
                    data = tomllib.load(f)
                server = data.get("server", server)
                topic = data.get("topic", topic)
                token = data.get("token", token)
            except (OSError, tomllib.TOMLDecodeError):
                pass
        elif toml_path is not None:
            # Fallback to a minimal INI-style parser if tomllib is unavailable.
            parser = configparser.ConfigParser()
            try:
                parser.read(toml_path)
                if "ntfy" in parser:
                    section = parser["ntfy"]
                    server = section.get("server", server)
                    topic = section.get("topic", topic)
                    token = section.get("token", token)
            except configparser.Error:
                pass

        return cls(server=server, topic=topic, token=token)

    def validate(self) -> None:
        if not self.topic:
            raise SystemExit(
                "No ntfy topic configured. Set NTFY_TOPIC, use --topic, or "
                "write a config file."
            )


def format_duration(seconds: float) -> str:
    """Format a duration in seconds as a human-readable string."""
    total = int(round(seconds))
    hours, remainder = divmod(total, 3600)
    minutes, seconds = divmod(remainder, 60)
    parts = []
    if hours:
        parts.append(f"{hours}h")
    if minutes or hours:
        parts.append(f"{minutes}m")
    parts.append(f"{seconds}s")
    return "".join(parts)


def build_message(title: str, exit_code: int, duration: float) -> tuple[str, int]:
    """Build the notification message and priority from the command result."""
    duration_str = format_duration(duration)
    if exit_code == 0:
        message = f"{title} finished in {duration_str}, exit 0"
        priority = "default"
    else:
        message = f"{title} finished in {duration_str}, exit {exit_code}"
        priority = "high"
    return message, priority


def post_notification(
    config: Config,
    title: str,
    message: str,
    priority: str = "default",
    tags: Optional[str] = None,
    on_prompt: bool = False,
) -> None:
    """POST a message to the ntfy topic."""
    url = f"{config.server}/{config.topic}"
    headers = {
        "Title": title,
        "Priority": priority,
    }
    if config.token:
        headers["Authorization"] = f"Bearer {config.token}"
    if tags:
        headers["Tags"] = tags
    if on_prompt:
        headers["X-Notify-Run-Prompt"] = "1"

    data = message.encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            if response.status >= 400:
                print(
                    f"notify-run: ntfy returned HTTP {response.status}",
                    file=sys.stderr,
                )
    except urllib.error.URLError as exc:
        print(f"notify-run: failed to post to ntfy: {exc}", file=sys.stderr)


def run_command(argv: list[str]) -> int:
    """Run a command, streaming output to the terminal, return exit code."""
    proc = subprocess.Popen(
        argv,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    assert proc.stdout is not None
    for line in proc.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
    return proc.wait()


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="notify-run",
        description="Run a command and post the result to ntfy.",
    )
    parser.add_argument("--title", default=None, help="Notification title.")
    parser.add_argument("--server", default=None, help="ntfy server URL.")
    parser.add_argument("--topic", default=None, help="ntfy topic name.")
    parser.add_argument("--token", default=None, help="ntfy bearer token.")
    parser.add_argument("--config", default=None, help="Path to config file.")
    parser.add_argument(
        "--priority",
        default=None,
        choices=["low", "default", "high", "urgent"],
        help="Override message priority.",
    )
    parser.add_argument("--tags", default=None, help="Comma-separated ntfy tags.")
    parser.add_argument(
        "--on-prompt",
        action="store_true",
        help="Post a message and wait for a reply (reply path is a stub).",
    )
    parser.add_argument(
        "command",
        nargs=argparse.REMAINDER,
        help="Command and arguments to run.",
    )
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)

    config = Config.load(Path(args.config) if args.config else None)
    if args.server:
        config.server = args.server
    if args.topic:
        config.topic = args.topic
    if args.token:
        config.token = args.token
    config.validate()

    # Find the "--" separator if present.
    cmd_args = args.command or []
    if "--" in cmd_args:
        sep = cmd_args.index("--")
        cmd_args = cmd_args[sep + 1 :]

    if not cmd_args:
        print("notify-run: no command to run.", file=sys.stderr)
        return 2

    title = args.title or os.path.basename(cmd_args[0])

    start = time.monotonic()
    exit_code = run_command(cmd_args)
    elapsed = time.monotonic() - start

    priority = args.priority
    if priority is None:
        _, priority = build_message(title, exit_code, elapsed)
    if exit_code != 0 and priority not in ("high", "urgent"):
        priority = "high"

    message, _ = build_message(title, exit_code, elapsed)

    post_notification(
        config,
        title=title,
        message=message,
        priority=priority,
        tags=args.tags,
        on_prompt=args.on_prompt,
    )

    if args.on_prompt:
        # Stub: the reply path is intentionally left unimplemented.
        print(
            "notify-run: --on-prompt is a stub; reply handling not implemented.",
            file=sys.stderr,
        )

    return exit_code


if __name__ == "__main__":
    sys.exit(main())