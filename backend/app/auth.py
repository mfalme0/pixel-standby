"""Bearer token auth.

The Android app holds the token and sends it as `Authorization: Bearer <token>`. The token is
compared in constant time so response timing cannot be used to recover it, and a failed
comparison is logged without the presented value.
"""

from __future__ import annotations

import logging
import secrets

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .config import Settings

logger = logging.getLogger(__name__)

bearer_scheme = HTTPBearer(auto_error=False)


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_service(request: Request):
    return request.app.state.quote_service


async def require_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    settings: Settings = Depends(get_settings),
) -> None:
    if credentials is None or not credentials.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    presented = credentials.credentials
    if not secrets.compare_digest(presented, settings.bearer_token):
        logger.warning("rejected request with an invalid bearer token")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )