import tempfile
import unittest
from pathlib import Path
from uuid import UUID

from request_debug_client_cache_reset import request_reset


class CacheResetRequestTest(unittest.TestCase):
    def test_targets_only_named_player_and_replaces_request_atomically(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "requests"
            first = request_reset("MGengine", directory)
            second = request_reset("MGengine", directory)
            self.assertNotEqual(first, second)
            self.assertEqual(str(UUID(second)), second)
            self.assertEqual((directory / "MGengine.request").read_text(), second + "\n")
            self.assertEqual([path.name for path in directory.iterdir()], ["MGengine.request"])

    def test_rejects_paths_and_shell_input_before_creating_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "requests"
            for player in ["../MGengine", "", "x;rm", "x/y", "a" * 17, "a b"]:
                with self.assertRaises(ValueError):
                    request_reset(player, directory)
            self.assertFalse(directory.exists())


if __name__ == "__main__":
    unittest.main()
