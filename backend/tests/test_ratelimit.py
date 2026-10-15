"""Token bucket behaviour."""

from __future__ import annotations

import asyncio

import pytest

from app.cache import TokenBucket

from .conftest import FakeClock, RecordingSleeper


async def test_full_bucket_serves_without_waiting(
    clock: FakeClock, sleeper: RecordingSleeper
) -> None:
    bucket = TokenBucket(capacity=3, window_seconds=60.0, clock=clock, sleep=sleeper)

    for _ in range(3):
        await bucket.acquire()

    assert sleeper.slept == []
    assert bucket.peek() == pytest.approx(0.0)


async def test_exhausted_bucket_waits_for_a_refill(
    clock: FakeClock, sleeper: RecordingSleeper
) -> None:
    # 2 tokens over 1 second => 2 tokens/second.
    bucket = TokenBucket(capacity=2, window_seconds=1.0, clock=clock, sleep=sleeper)

    await bucket.acquire()
    await bucket.acquire()
    await bucket.acquire()

    # One full bucket-second had to pass.
    assert sleeper.slept == pytest.approx([0.5])


async def test_refill_is_capped_at_capacity(
    clock: FakeClock, sleeper: RecordingSleeper
) -> None:
    bucket = TokenBucket(capacity=4, window_seconds=1.0, clock=clock, sleep=sleeper)

    await bucket.acquire()
    clock.advance(1000)  # idle far longer than it takes to fill the bucket
    assert bucket.peek() == pytest.approx(4.0)


async def test_steady_state_matches_the_configured_rate(
    clock: FakeClock, sleeper: RecordingSleeper
) -> None:
    bucket = TokenBucket(capacity=1, window_seconds=10.0, clock=clock, sleep=sleeper)

    for _ in range(5):
        await bucket.acquire()

    # 1 token per 10 seconds, so four waits of 10s each after the initial free one.
    assert sleeper.slept == pytest.approx([10.0, 10.0, 10.0, 10.0])
    assert clock.now == pytest.approx(1_040.0)


async def test_multi_token_acquire_waits_for_the_whole_amount(
    clock: FakeClock, sleeper: RecordingSleeper
) -> None:
    bucket = TokenBucket(capacity=4, window_seconds=4.0, clock=clock, sleep=sleeper)

    await bucket.acquire(tokens=2.0)
    assert bucket.peek() == pytest.approx(2.0)

    await bucket.acquire(tokens=2.0)
    assert sleeper.slept == []


async def test_concurrent_callers_all_eventually_pass(
    clock: FakeClock, sleeper: RecordingSleeper
) -> None:
    """The lock is what stops two coroutines spending the same token."""
    bucket = TokenBucket(capacity=2, window_seconds=2.0, clock=clock, sleep=sleeper)

    await asyncio.gather(*(bucket.acquire() for _ in range(6)))

    # 6 requests at 1/second (capacity 2 over 2s) means the last one waits 4 seconds.
    assert clock.now >= 1_004.0 - 0.001


async def test_acquiring_more_than_capacity_is_a_programming_error(
    clock: FakeClock, sleeper: RecordingSleeper
) -> None:
    bucket = TokenBucket(capacity=2, window_seconds=60.0, clock=clock, sleep=sleeper)
    with pytest.raises(ValueError):
        await bucket.acquire(tokens=3.0)


@pytest.mark.parametrize(
    ("capacity", "window"),
    [(0, 60.0), (-1, 60.0), (5, 0), (5, -1)],
)
def test_invalid_configuration_is_rejected(
    clock: FakeClock, capacity: int, window: float
) -> None:
    with pytest.raises(ValueError):
        TokenBucket(capacity=capacity, window_seconds=window, clock=clock)


def test_rate_is_derived_from_capacity_and_window(clock: FakeClock) -> None:
    bucket = TokenBucket(capacity=30, window_seconds=60.0, clock=clock)
    assert bucket.rate_per_second == pytest.approx(0.5)