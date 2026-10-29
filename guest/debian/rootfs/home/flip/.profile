# ~/.profile of the desktop user: Debian's default, then X on tty1.
if [ -n "$BASH_VERSION" ] && [ -f "$HOME/.bashrc" ]; then
    . "$HOME/.bashrc"
fi
if [ -d "$HOME/bin" ]; then
    PATH="$HOME/bin:$PATH"
fi

# tty1 logs in on its own (inittab) and starts the desktop. When X ends (the start menu's "Exit to
# the text console", or X failing) this login stays at a shell; it is not exec'd, since then the
# login would end with X and init's respawned autologin would start X again at once, over and over
# if X keeps failing. The other consoles and COM1 stay plain shells.
if [ -z "$DISPLAY" ] && [ "$(tty)" = /dev/tty1 ]; then
    startx -- -nolisten tcp > "$HOME/.startx.log" 2>&1
    echo "The desktop has ended (its log: ~/.startx.log). Type startx to start it again."
fi
