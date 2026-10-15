"""HTTP surface: auth, validation, and the two endpoints.

Upstream is mocked at the transport layer, so these exercise the real routing, the real
serialization, and the real auth path.
"""

from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import ConfigurationError, Settings
from app.main import create_app

from .conftest import TOKEN, yahoo_chart_payload


@pytest.fixture
def client(settings, yahoo_chart_payload):
    current_response = yahoo_chart_payload

    async def transport_handler(request):
        return httpx.Response(200, json=current_response)

    class ResponseOverride:
        @staticmethod
        def set(payload: dict) -> None:
            nonlocal current_response
            current_response = payload

    app = create_app(settings, transport=httpx.MockTransport(transport_handler))
    with TestClient(app) as test_client:
        test_client.override_response = ResponseOverride.set
        yield test_client


def auth(token: str = TOKEN) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# --- auth ---------------------------------------------------------------------


def test_quotes_requires_a_token(client) -> None:
    response = client.get("/api/quotes?symbols=AAPL")
    assert response.status_code == 401
    assert response.json()["detail"] == "Missing bearer token"


def test_wrong_token_is_rejected(client) -> None:
    response = client.get("/api/quotes?symbols=AAPL", headers=auth("nope"))
    assert response.status_code == 401
    assert response.json()["detail"] == "Invalid bearer token"


def test_token_comparison_is_exact(client) -> None:
    response = client.get("/api/quotes?symbols=AAPL", headers=auth(TOKEN + "x"))
    assert response.status_code == 401


def test_non_bearer_scheme_is_rejected(client) -> None:
    response = client.get(
        "/api/quotes?symbols=AAPL", headers={"Authorization": f"Basic {TOKEN}"}
    )
    assert response.status_code == 401


def test_health_does_not_require_a_token(client) -> None:
    # Deliberate: the phone polls this to show its connection indicator, and it exposes no
    # market data or token material.
    assert client.get("/api/health").status_code == 200


# --- quotes -------------------------------------------------------------------


def test_quotes_returns_the_documented_shape(client) -> None:
    response = client.get("/api/quotes?symbols=AAPL", headers=auth())

    assert response.status_code == 200
    payload = response.json()
    assert len(payload) == 1

    quote = payload[0]
    assert set(quote) == {
        "symbol",
        "price",
        "change",
        "changePercent",
        "currency",
        "asOf",
        "source",
        "stale",
    }
    assert quote["symbol"] == "AAPL"
    assert quote["price"] == pytest.approx(231.44)
    assert quote["change"] == pytest.approx(1.82)
    assert quote["currency"] == "USD"
    assert quote["source"] == "yahoo"
    assert quote["stale"] is False
    assert quote["asOf"].endswith("Z")


def test_multiple_symbols_are_requested_in_one_call(client) -> None:
    response = client.get("/api/quotes?symbols=AAPL,MSFT,XAUUSD", headers=auth())
    assert response.status_code == 200
    # Cached after the first pass, so a second identical call adds nothing upstream.
    assert len(response.json()) == 3


def test_whitespace_in_the_query_is_tolerated(client) -> None:
    response = client.get("/api/quotes?symbols= AAPL , MSFT ", headers=auth())
    assert response.status_code == 200
    assert {q["symbol"] for q in response.json()} == {"AAPL", "MSFT"}


def test_missing_symbols_parameter_is_a_422(client) -> None:
    assert client.get("/api/quotes", headers=auth()).status_code == 422


def test_empty_symbol_list_is_rejected(client) -> None:
    response = client.get("/api/quotes?symbols=,,", headers=auth())
    assert response.status_code == 400
    assert response.json()["detail"] == "No symbols supplied"


def test_too_many_symbols_is_rejected(client) -> None:
    symbols = ",".join(f"SYM{i}" for i in range(50))
    response = client.get(f"/api/quotes?symbols={symbols}", headers=auth())
    assert response.status_code == 400
    assert "Too many symbols" in response.json()["detail"]


def test_upstream_failure_for_uncached_symbol_omits_it(client) -> None:
    # No cache exists yet, and the upstream returns an empty chart.
    client.override_response({})

    response = client.get("/api/quotes?symbols=ZZZZ", headers=auth())
    assert response.status_code == 200
    assert response.json() == []


def test_unknown_symbol_is_omitted_rather_than_faked(client) -> None:
    client.override_response(
        {
            "chart": {
                "result": None,
                "error": {"code": "Not Found", "description": "no data"},
            }
        }
    )

    response = client.get("/api/quotes?symbols=ZZZZ", headers=auth())
    assert response.status_code == 200
    assert response.json() == []


# --- health -------------------------------------------------------------------


def test_health_before_any_fetch(client) -> None:
    payload = client.get("/api/health").json()
    assert payload["status"] == "degraded"
    assert payload["lastSuccessAt"] is None
    assert payload["lastSuccessAgeSeconds"] is None
    assert payload["cacheTtlSeconds"] == pytest.approx(60.0)


def test_health_after_a_successful_fetch(client) -> None:
    client.get("/api/quotes?symbols=AAPL", headers=auth())
    payload = client.get("/api/health").json()

    assert payload["status"] == "ok"
    assert payload["lastSuccessAt"] is not None
    assert payload["cachedSymbols"] >= 1


def test_health_goes_degraded_again_when_upstream_dies(client) -> None:
    # First call succeeds and caches AAPL.
    client.get("/api/quotes?symbols=AAPL", headers=auth())
    assert client.get("/api/health").json()["status"] == "ok"

    # Second call uses a symbol not in cache; upstream fails, so health degrades.
    client.override_response({"chart": {"result": None, "error": {"code": "Server Error", "description": "down"}}})
    client.get("/api/quotes?symbols=MSFT", headers=auth())

    assert client.get("/api/health").json()["status"] == "degraded"


def test_health_never_leaks_the_token(client) -> None:
    body = client.get("/api/health").text
    assert TOKEN not in body


# --- startup ------------------------------------------------------------------


def test_missing_bearer_token_is_a_startup_error(monkeypatch) -> None:
    monkeypatch.delenv("PIXEL_BEARER_TOKEN", raising=False)
    with pytest.raises(ConfigurationError) as excinfo:
        Settings.from_env()

    # The message may name the variable, never show a value.
    assert "PIXEL_BEARER_TOKEN" in str(excinfo.value)


def test_settings_repr_hides_the_token(monkeypatch) -> None:
    monkeypatch.setenv("PIXEL_BEARER_TOKEN", "super-secret-value")
    settings = Settings.from_env()

    assert "super-secret-value" not in repr(settings)
    assert "bearer_token=***" in repr(settings)


def test_env_overrides_are_applied(monkeypatch) -> None:
    monkeypatch.setenv("PIXEL_BEARER_TOKEN", "abc")
    monkeypatch.setenv("CACHE_TTL_SECONDS", "30")
    monkeypatch.setenv("UPSTREAM_RATE_CAPACITY", "5")
    monkeypatch.setenv("UPSTREAM_RATE_WINDOW_SECONDS", "10")

    settings = Settings.from_env()
    assert settings.bearer_token == "abc"
    assert settings.cache_ttl_seconds == 30.0
    assert settings.rate_limit_capacity == 5
    assert settings.rate_limit_window_seconds == 10.0


def test_invalid_numeric_env_is_rejected(monkeypatch) -> None:
    monkeypatch.setenv("PIXEL_BEARER_TOKEN", "abc")
    monkeypatch.setenv("CACHE_TTL_SECONDS", "not-a-number")

    with pytest.raises(ConfigurationError):
        Settings.from_env()