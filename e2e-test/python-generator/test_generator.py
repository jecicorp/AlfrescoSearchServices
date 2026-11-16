import os
import subprocess
import sys
import tempfile
import unittest

import yaml

GENERATOR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'generator.py')


def generate(communication):
    with tempfile.TemporaryDirectory() as outputDir:
        subprocess.run([sys.executable, GENERATOR, '-comm', communication, '-o', outputDir],
                       check=True, stdout=subprocess.DEVNULL)
        with open(outputDir + '/docker-compose.yml') as f:
            return yaml.safe_load(f)


class TrackersChannelTest(unittest.TestCase):

    def test_mtls_guards_the_trackers_with_the_shared_secret(self):
        env = generate('mtls')['services']['trackers']['environment']
        self.assertEqual('secret', env['ALFRESCO_TRACKER_SERVER_SECURECOMMS'])
        self.assertEqual('secret', env['ALFRESCO_TRACKER_SERVER_SHAREDSECRET'])

    def test_mtls_repository_presents_the_secret_over_plain_http(self):
        javaOpts = generate('mtls')['services']['alfresco']['environment']['JAVA_OPTS']
        self.assertIn('-Dsolr.secureComms=https', javaOpts)
        self.assertIn('-Dsolr.tracker.secureComms=secret', javaOpts)
        self.assertIn('-Dsolr.tracker.sharedSecret=secret', javaOpts)

    def test_mtls_keeps_the_trackers_in_plain_http(self):
        env = generate('mtls')['services']['trackers']['environment']
        self.assertNotIn('ALFRESCO_TRACKER_SERVER_SSL_ENABLED', env)
        self.assertEqual('none', env['ALFRESCO_TRACKER_REPOSITORY_SECURECOMMS'])

    def test_none_leaves_the_trackers_unauthenticated(self):
        env = generate('none')['services']['trackers']['environment']
        self.assertEqual('none', env['ALFRESCO_TRACKER_SERVER_SECURECOMMS'])
        self.assertNotIn('ALFRESCO_TRACKER_SERVER_SHAREDSECRET', env)


if __name__ == '__main__':
    unittest.main()
