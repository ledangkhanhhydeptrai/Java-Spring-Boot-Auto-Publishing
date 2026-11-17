#!/usr/bin/env python3
"""Offline SocialHub queue/load simulator. No DB, RabbitMQ or Meta traffic."""
import argparse
import concurrent.futures
import datetime as dt
import json
import queue
import random
import threading
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description='Offline load test for simulated publishing jobs, NEVER calls Meta or database')
    parser.add_argument('--accounts', type=int, default=100)
    parser.add_argument('--jobs-per-account', type=int, default=10)
    parser.add_argument('--workers', type=int, default=8)
    parser.add_argument('--latency-ms', type=float, default=25)
    parser.add_argument('--failure-rate', type=float, default=0)
    parser.add_argument('--output', default='mock-load-results.json')
    args = parser.parse_args()
    if not (1 <= args.accounts <= 100000 and 1 <= args.jobs_per_account <= 10000 and 1 <= args.workers <= 256):
        parser.error('Invalid accounts/jobs-per-account/workers')
    if not 0 <= args.failure_rate <= 1 or args.latency_ms < 0:
        parser.error('Invalid failure rate or latency')
    total = args.accounts * args.jobs_per_account
    q = queue.Queue()
    for a in range(args.accounts):
        account_id = f'MOCK_PAGE_{a+1:04d}'
        for j in range(args.jobs_per_account):
            q.put((str(uuid.uuid5(uuid.NAMESPACE_OID, f'{account_id}:{j}')), account_id))
    claimed = set()
    lock = threading.Lock()
    status = {'PUBLISHED_MOCK': 0, 'FAILED_MOCK': 0, 'DUPLICATE_CLAIM': 0}
    start = time.perf_counter()
    def worker(worker_index):
        rng = random.Random(42 + worker_index)
        while True:
            try:
                job_id, _ = q.get_nowait()
            except queue.Empty:
                return
            try:
                with lock:
                    if job_id in claimed:
                        status['DUPLICATE_CLAIM'] += 1
                        continue
                    claimed.add(job_id)
                if args.latency_ms:
                    time.sleep(args.latency_ms / 1000)
                result = 'FAILED_MOCK' if rng.random() < args.failure_rate else 'PUBLISHED_MOCK'
                with lock:
                    status[result] += 1
            finally:
                q.task_done()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        list(pool.map(worker, range(args.workers)))
    elapsed = time.perf_counter() - start
    result = {
        'mode': 'OFFLINE_MOCK_ONLY', 'meta_requests': 0, 'database_writes': 0,
        'rabbitmq_messages': 0, 'accounts': args.accounts, 'jobs': total,
        'workers': args.workers, 'latency_ms': args.latency_ms,
        'elapsed_seconds': round(elapsed, 3),
        'jobs_per_second': round(total/elapsed, 2) if elapsed else 0,
        'status_counts': status,
        'timestamp_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
        'warning': 'This is a synthetic executor benchmark, NOT a SocialHub integration test and NOT proof of Meta publishing capability.'
    }
    with open(args.output, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
