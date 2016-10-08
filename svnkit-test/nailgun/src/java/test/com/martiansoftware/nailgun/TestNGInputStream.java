/*   

  Copyright 2004, Martian Software, Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

*/

package com.martiansoftware.nailgun;

import java.io.ByteArrayInputStream;

import junit.framework.TestCase;

/**
 * 
 * @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
 */
public class TestNGInputStream extends TestCase {

	private static final byte[] TESTDATA = {0x00, 0x00, 0x00, 0x0e, '0',
			'T',  'h',  'i',  's',  ' ',  'i',  's',  ' ',  'a',  ' ', 't', 'e', 's', 't',
			0x00, 0x00, 0x00, 0x01, '0', '!',
			0x00, 0x00, 0x00, 0x00, '.'
	};
	
	private static final String TESTSTRING = "This is a test!";
	
	public void testNGInputStreamIntoArray() throws Exception {
		NGInputStream in = new NGInputStream(new ByteArrayInputStream(TESTDATA));
		
		assertTrue(in.available() > 0);
		assertFalse(in.markSupported());
		
		byte[] buf = new byte[1024];
		int bytesRead = 0;
		int totalBytes = 0;
		StringBuffer sbuf = new StringBuffer();
		do {
			bytesRead = in.read(buf);
			if (bytesRead > 0) {
				totalBytes += bytesRead;
				sbuf.append(new String(buf, 0, bytesRead, "US-ASCII"));
			}
		} while (bytesRead > 0);
		assertEquals(15, totalBytes);
		assertEquals(TESTSTRING, sbuf.toString());
	}
	
	public void testNGInputStreamCharByChar() throws Exception {
		StringBuffer buf = new StringBuffer();
		NGInputStream in = new NGInputStream(new ByteArrayInputStream(TESTDATA));
		int c = in.read();
		while (c != -1) {
			buf.append((char) c);
			c = in.read();
		}
		assertEquals(TESTSTRING, buf.toString());
	}
}
