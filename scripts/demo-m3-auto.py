#!/usr/bin/env python3
"""自动成功：有效Mock签约 → 异步支付 → 核验PAID；不执行真实支付。"""
from m3_demo import run
if __name__ == '__main__':
    run('auto')
