#!/usr/bin/env python3
"""未知冻结：Mock付款后查单超时 → 两入口409 → 只查同一attempt。"""
from m3_demo import run
if __name__ == '__main__':
    run('unknown')
