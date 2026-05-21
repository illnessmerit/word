# word

## Her Words, Not Mine

> What's `word`?

`word` is a Neovim plugin that generates sentence-by-sentence rewrites.

## Setup

> How do I set up `word`?

1. Make sure you're using a Mac.

1. Install [Homebrew](https://brew.sh/#install).

1. Install [Alacritty](https://github.com/alacritty/alacritty).

1. Install [`lazy.nvim`](https://github.com/folke/lazy.nvim).

1. Merge these bindings into your Alacritty configuration:

   ```toml
   [keyboard]
   bindings = [
     { chars = "\u001bwf", key = "F", mods = "Command" },
     { chars = "\u001bwj", key = "J", mods = "Command" },
     { chars = "\u001bwk", key = "K", mods = "Command" },
   ]
   ```

1. Add this block to your `lazy.nvim` configuration:

   ```lua
   {
   	"8ta4/word",
   	build = "./install.sh",
   	dependencies = {
   		"8ta4/sentence",
   		build = "./install.sh",
   	},
   	keys = {
   		{
   			"<M-w>f",
   			function()
   				require("word").suggest()
   			end,
   			mode = { "i", "n", "x" },
   		},
   		{
   			"<M-w>j",
   			function()
   				require("word").apply(2)
   			end,
   			mode = { "i", "n", "x" },
   		},
   		{
   			"<M-w>k",
   			function()
   				require("word").apply(1)
   			end,
   			mode = { "i", "n", "x" },
   		},
   	},
   	opts = {
   		styles = {
   			{
   				name = "casual",
   				prompt = "Check if the sentence is grammatically correct. Explain any errors. Keep the formatting. Give two casual rewrites.",
   			},
   		},
   	},
   }
   ```

1. Copy an API key from [the Groq website](https://console.groq.com).

1. Open a terminal.

1. Run the following commands:

   ```bash
   mkdir -p ~/.config/word/
   pbpaste > ~/.config/word/groq
   brew install node
   npm install -g neovim
   nvim --headless "+Lazy load word" +UpdateRemotePlugins +qa!
   ```

## Usage

> How do I get suggestions from Normal mode?

Press `⌘ + f`. Think fix.

> How do I apply the first suggestion from Normal mode?

Press `⌘ + k`. Vim uses `k` for up.

> How do I apply the second suggestion from Normal mode?

Press `⌘ + j`. Vim uses `j` for down.

> Can I generate suggestions for multiple sentences at once?

Yes. Select the text in Visual mode and press `⌘ + f`. `word` rewrites every sentence that would be targeted if you pressed `⌘ + f` at every character inside your visual selection.

> Can I add more styles?

Yes.

1. Add the number keybindings to your Alacritty configuration:

   ```toml
   [keyboard]
   bindings = [
     { chars = "\u001bw1", key = "Key1", mods = "Command" },
     { chars = "\u001bw2", key = "Key2", mods = "Command" },
     { chars = "\u001bwf", key = "F", mods = "Command" },
     { chars = "\u001bwj", key = "J", mods = "Command" },
     { chars = "\u001bwk", key = "K", mods = "Command" },
   ]
   ```

1. Add the number keybindings and the second style to your `lazy.nvim` configuration:

   ```lua
   local base = "Check if the sentence is grammatically correct. Explain any errors. Keep the formatting. "

   {
   	"8ta4/word",
   	build = "./install.sh",
   	dependencies = {
   		"8ta4/sentence",
   		build = "./install.sh",
   	},
   	keys = {
   		{
   			"<M-w>1",
   			function()
   				require("word").style(1)
   			end,
   			mode = { "i", "n", "x" },
   		},
   		{
   			"<M-w>2",
   			function()
   				require("word").style(2)
   			end,
   			mode = { "i", "n", "x" },
   		},
   		{
   			"<M-w>f",
   			function()
   				require("word").suggest()
   			end,
   			mode = { "i", "n", "x" },
   		},
   		{
   			"<M-w>j",
   			function()
   				require("word").apply(2)
   			end,
   			mode = { "i", "n", "x" },
   		},
   		{
   			"<M-w>k",
   			function()
   				require("word").apply(1)
   			end,
   			mode = { "i", "n", "x" },
   		},
   	},
   	opts = {
   		styles = {
   			{ name = "casual", prompt = base .. "Give two casual rewrites." },
   			{ name = "formal", prompt = base .. "Give two formal rewrites." },
   		},
   	},
   }
   ```

> How do I select the second style in the configuration?

Press `⌘ + 2`. It picks the second entry in your `styles` list.
