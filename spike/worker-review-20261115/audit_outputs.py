"""Check persisted reports for known configuration secrets without printing any."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
sys.dont_write_bytecode = True
out = Path(__file__).resolve().parent
with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    spec = importlib.util.spec_from_file_location('private_worker_audit', Path.home()/'.gemini/config/tools/llm-worker/worker.py')
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    cfg = worker.load_config()
secrets = [v for k,v in cfg.items() if len(v or '') >= 8 and any(t in k.upper() for t in ['KEY','TOKEN','SECRET','PASSWORD'])]
leaks = []
count = 0
for file in out.iterdir():
    if file.is_file():
        text = file.read_text(encoding='utf-8')
        count += 1
        if any(secret in text for secret in secrets):
            leaks.append(file.name)
if leaks:
    raise SystemExit('Secret audit FAILED; files: '+','.join(leaks))
results = json.loads((out/'results.json').read_text(encoding='utf-8'))
assert len(results) == 22
assert len({r['task'] for r in results}) == len(results), 'duplicate task'
assert sum(r['status']=='completed' for r in results) == 4
assert sum(r['status']=='skipped' for r in results) == 3
assert sum(r['status']=='error' for r in results) == 15
print('PASS output secret audit: '+str(count)+' files; 22 unique tasks; 4 nonempty, 15 errors, 3 skipped')
