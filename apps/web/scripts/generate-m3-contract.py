"""Regenerate M3 browser DTOs and validation schema from the OpenAPI contract."""
import argparse
import json
from pathlib import Path
import yaml

root = Path(__file__).resolve().parents[3]
all_schemas = yaml.safe_load((root / 'contracts/openapi.yaml').read_text())['components']['schemas']
schemas = {key: value for key, value in all_schemas.items() if key.startswith('M3')}

def ts_type(schema):
    if '$ref' in schema:
        result = schema['$ref'].split('/')[-1].removeprefix('M3')
    elif 'enum' in schema:
        result = ' | '.join(json.dumps(item) for item in schema['enum'])
    elif schema['type'] == 'array':
        result = f"({ts_type(schema['items'])})[]"
    elif schema['type'] == 'object':
        required = schema.get('required', [])
        result = '{ ' + ' '.join(f"{key}{'' if key in required else '?'}: {ts_type(value)};" for key, value in schema.get('properties', {}).items()) + ' }'
    else:
        result = {'integer': 'number', 'number': 'number', 'boolean': 'boolean', 'string': 'string'}[schema['type']]
    return result + (' | null' if schema.get('nullable') else '')

source = '// Generated from contracts/openapi.yaml; run python3 apps/web/scripts/generate-m3-contract.py.\n'
source += '\n'.join(f'export type {name.removeprefix("M3")} = {ts_type(value)};' for name, value in schemas.items()) + '\n'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check', action='store_true', help='Fail if committed browser contract files differ from OpenAPI.')
args = parser.parse_args()
outputs = {
    root / 'apps/web/src/domain/m3-types.ts': source,
    root / 'apps/web/src/domain/m3-contract.json': json.dumps(schemas, ensure_ascii=False, indent=2) + '\n',
}
if args.check:
    stale = [str(path.relative_to(root)) for path, expected in outputs.items() if not path.exists() or path.read_text() != expected]
    if stale:
        raise SystemExit('M3 browser contract is stale: ' + ', '.join(stale))
    print('M3 browser DTOs and validation schema match contracts/openapi.yaml')
else:
    for path, contents in outputs.items():
        path.write_text(contents)
