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

package com.martiansoftware.nailgun.examples;

import com.martiansoftware.nailgun.NGContext;

/**
 * Prompts the user for input using a JOptionPane, and displays the
 * result to the client's stdout.  If the user clicks "cancel", the
 * client exits with exit code 1.
 * 
 * @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
 */
public class Prompt {

	public static void nailMain(NGContext context) {
		String result = javax.swing.JOptionPane.showInputDialog(null, "Input:");
		if (result == null) {
			context.exit(1);
		} else {
			context.out.println(result);
		}
	}
}
