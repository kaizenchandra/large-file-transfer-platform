import unittest,urllib.request,urllib.error,os
URL='http://localhost:19091'
class SandboxContract(unittest.TestCase):
 def test_unauthorized(self):
  with self.assertRaises(urllib.error.HTTPError) as e:urllib.request.urlopen(urllib.request.Request(URL+'/pdf',data=b'%PDF-1.4'))
  self.assertEqual(e.exception.code,401);e.exception.close()
 def test_kind_allowlist(self):
  with self.assertRaises(urllib.error.HTTPError) as e:urllib.request.urlopen(urllib.request.Request(URL+'/shell',data=b'x',headers={'Authorization':'Bearer '+os.environ['SANDBOX_TOKEN']}))
  self.assertEqual(e.exception.code,400);e.exception.close()
 def test_invalid_pdf(self):
  with self.assertRaises(urllib.error.HTTPError) as e:urllib.request.urlopen(urllib.request.Request(URL+'/pdf',data=b'%PDF-broken',headers={'Authorization':'Bearer '+os.environ['SANDBOX_TOKEN']}),timeout=60)
  self.assertEqual(e.exception.code,422);e.exception.close()
if __name__=='__main__':unittest.main()
