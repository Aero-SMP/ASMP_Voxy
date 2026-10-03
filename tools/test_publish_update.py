import hashlib
from pathlib import Path
import tempfile
import unittest
from tools.publish_update import publish


class PublicationTest(unittest.TestCase):
    def test_publish_then_reject_replacement(self):
        root = Path(__file__).resolve().parents[1]
        work = root / '.verification'
        work.mkdir(exist_ok=True)
        jar = root / 'client/build/libs/voxy-rewrite-client-0.3.0-beta-debug.jar'
        with tempfile.TemporaryDirectory(dir=work) as directory:
            feed = Path(directory)
            announcement = publish(jar, 'client', feed)
            values = dict(line.split('=', 1) for line in announcement.read_text().splitlines())
            artifact = announcement.parent / values['file']
            self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), values['sha256'])
            original = announcement.read_bytes()
            with self.assertRaises(ValueError):
                publish(jar, 'client', feed)
            self.assertEqual(original, announcement.read_bytes())
            self.assertFalse(list(announcement.parent.glob('*.part')))
            with self.assertRaises(ValueError):
                publish(jar, 'server', feed)


if __name__ == '__main__':
    unittest.main()
