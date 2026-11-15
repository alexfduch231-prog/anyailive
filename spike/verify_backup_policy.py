"""Host-only XML policy regression for the anyAIlive backup posture.

Reads app/AndroidManifest.xml, app/res/xml/backup_rules.xml,
app/res/xml/data_extraction_rules.xml and SECURITY.md as text.
No Android SDK, no build, no device: it only asserts that

  * private app data (database / sharedpref / files / external / root) is excluded
    from Auto Backup and from device-to-device transfer for API 24-34,
  * cleartext HTTP stays deliberately allowed (arbitrary user-approved self-hosted
    endpoints are a supported feature, not a bug),
  * the rest of the manifest is untouched,
  * SECURITY.md documents both facts honestly.

Run from any directory:  python spike/verify_backup_policy.py
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app'
MANIFEST = APP / 'AndroidManifest.xml'
BACKUP_RULES = APP / 'res' / 'xml' / 'backup_rules.xml'
EXTRACTION_RULES = APP / 'res' / 'xml' / 'data_extraction_rules.xml'
SECURITY = ROOT / 'SECURITY.md'

ANDROID = '{http://schemas.android.com/apk/res/android}'
# Every private domain the app writes to: chat DB, "secrets" preferences,
# recorded WAV clips (files), anything staged in external storage.
DOMAINS = {'root', 'file', 'database', 'sharedpref', 'external'}

ACTIVITIES = {
    '.ui.MainActivity': 'true',
    '.ui.SettingsActivity': 'false',
    '.ui.ModelsActivity': 'false',
    '.ui.ModelEditActivity': 'false',
    '.ui.ChatActivity': 'false',
    '.ui.LiveActivity': 'false',
}
PERMISSIONS = {
    'android.permission.INTERNET',
    'android.permission.RECORD_AUDIO',
    'android.permission.MODIFY_AUDIO_SETTINGS',
}
QUERY_ACTIONS = {'android.intent.action.TTS_SERVICE', 'android.speech.RecognitionService'}
APPLICATION_ATTRIBUTES = {
    'name', 'label', 'icon', 'theme', 'usesCleartextTraffic',
    'allowBackup', 'fullBackupContent', 'dataExtractionRules',
}

results = []


def check(label, ok, detail=''):
    results.append((label, bool(ok), detail))
    suffix = '' if ok else ('  [' + str(detail) + ']' if detail else '')
    print(('ok   ' if ok else 'FAIL ') + label + suffix)


def attr(element, name, default=None):
    return element.get(ANDROID + name, default)


def load_xml(path):
    if not path.is_file():
        check('file.' + path.name + '.exists', False, 'missing ' + str(path))
        return None
    try:
        return ET.parse(str(path)).getroot()
    except ET.ParseError as error:
        check('file.' + path.name + '.parses', False, error)
        return None


def whole_domains(parent):
    """Domains excluded wholesale (path '.' or empty) inside one rules element."""
    covered = set()
    for element in parent.findall('exclude'):
        domain = element.get('domain')
        if element.get('path', '.') in ('.', ''):
            covered.add(domain)
    return covered


def check_manifest(manifest):
    check('manifest.package', manifest.get('package') == 'ru.alexv.anyailive', manifest.get('package'))

    permissions = {attr(node, 'name') for node in manifest.findall('uses-permission')}
    check('manifest.permissions', permissions == PERMISSIONS, sorted(permissions))

    actions = {attr(intent.find('action'), 'name')
               for intent in manifest.findall('queries/intent')}
    check('manifest.queries', actions == QUERY_ACTIONS, sorted(actions))

    application = manifest.find('application')
    if application is None:
        check('manifest.application', False, 'no <application>')
        return

    # Cleartext must stay enabled on purpose: arbitrary HTTP self-hosted endpoints
    # are user-approved, so this is a documented trade-off, not a regression.
    check('manifest.cleartext.still_allowed', attr(application, 'usesCleartextTraffic') == 'true',
          attr(application, 'usesCleartextTraffic'))

    check('manifest.application.name', attr(application, 'name') == '.App', attr(application, 'name'))
    check('manifest.application.label', attr(application, 'label') == '@string/app_name',
          attr(application, 'label'))
    check('manifest.application.icon', attr(application, 'icon') == '@drawable/ic_launcher',
          attr(application, 'icon'))
    check('manifest.application.theme', attr(application, 'theme') == '@style/AppTheme',
          attr(application, 'theme'))

    extra = {name for name in application.keys() if name.startswith(ANDROID)}
    extra = {name[len(ANDROID):] for name in extra} - APPLICATION_ATTRIBUTES
    check('manifest.application.no_unexpected_fields', not extra, sorted(extra))

    activities = {}
    for node in application.findall('activity'):
        activities[attr(node, 'name')] = attr(node, 'exported')
    check('manifest.activities', activities == ACTIVITIES, activities)

    # Backup policy itself.
    check('manifest.allowBackup.false', attr(application, 'allowBackup') == 'false',
          attr(application, 'allowBackup'))
    check('manifest.fullBackupContent.linked',
          attr(application, 'fullBackupContent') == '@xml/backup_rules',
          attr(application, 'fullBackupContent'))
    check('manifest.dataExtractionRules.linked',
          attr(application, 'dataExtractionRules') == '@xml/data_extraction_rules',
          attr(application, 'dataExtractionRules'))


def check_backup_rules(rules):
    # API 24-30 (full-backup-content).
    check('backup_rules.root', rules.tag == 'full-backup-content', rules.tag)
    check('backup_rules.excludes_all_private_domains', DOMAINS <= whole_domains(rules),
          sorted(DOMAINS - whole_domains(rules)))
    check('backup_rules.no_whitelisted_paths', rules.findall('include') == [],
          [element.attrib for element in rules.findall('include')])


def check_extraction_rules(rules):
    # API 31+ (data-extraction-rules): cloud backup and device transfer are
    # separate switches, and on API 31+ allowBackup="false" does not stop D2D.
    check('extraction_rules.root', rules.tag == 'data-extraction-rules', rules.tag)
    for section in ('cloud-backup', 'device-transfer'):
        node = rules.find(section)
        if node is None:
            check('extraction_rules.' + section + '.present', False, 'no <' + section + '>')
            continue
        covered = whole_domains(node)
        check('extraction_rules.' + section + '.excludes_all_private_domains',
              DOMAINS <= covered, sorted(DOMAINS - covered))
        check('extraction_rules.' + section + '.no_whitelisted_paths',
              node.findall('include') == [],
              [element.attrib for element in node.findall('include')])


def check_security_doc():
    if not SECURITY.is_file():
        check('security.exists', False, 'missing')
        return
    text = SECURITY.read_text(encoding='utf-8')
    low = text.lower()

    # Backup is off and this is documented, including that the keystore key is
    # not restorable.
    check('security.backup_documented', 'резервн' in low and 'отключ' in low)
    check('security.backup_lists_private_data',
          'переписк' in low and 'sharedpreferences' in low and 'аудио' in low)

    # Honest cleartext wording: not localhost-only, key/audio go out in the clear.
    check('security.http.not_localhost_only', 'не только localhost' in low)
    check('security.http.any_user_host', 'любой http-адрес' in low)
    check('security.http.key_in_clear', 'api-ключ' in low and 'открытым текстом' in low)
    check('security.http.audio_in_clear', 'аудио' in low and 'открытым текстом' in low)
    check('security.http.old_localhost_only_claim_removed',
          'для локальных совместимых серверов' not in low)


def main():
    manifest = load_xml(MANIFEST)
    if manifest is not None:
        check_manifest(manifest)

    backup = load_xml(BACKUP_RULES)
    if backup is not None:
        check_backup_rules(backup)

    extraction = load_xml(EXTRACTION_RULES)
    if extraction is not None:
        check_extraction_rules(extraction)

    check_security_doc()

    failed = [label for label, ok, _ in results if not ok]
    if failed:
        print('')
        print('FAIL_BACKUP_POLICY %d of %d checks failed' % (len(failed), len(results)))
        return 1
    print('')
    print('PASS_BACKUP_POLICY %d checks' % len(results))
    return 0


if __name__ == '__main__':
    sys.exit(main())
