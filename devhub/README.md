# The One Dev Hub

Browser-based editor and Git control panel for **The One** (`app/`) and **The One Car** (`carradio/`).

## Current server layout

- Public document root: `/home/vanawiwj/dev.vanaggelen.com`
- Private Dev Hub core: `/home/vanawiwj/devhub-core`
- Git repository clone: `/home/vanawiwj/repos/the-one-apps`
- Public URL: `https://dev.rubenvanaggelen.com`

The public files deliberately rely on the private core files already created on the Namecheap server:

- `config.php`
- `git.php`
- `files.php`

## Included functions

- Open The One and The One Car
- Browse folders and files
- Read source files
- Edit and save existing source files
- Show Git branch, last commit and working-tree state
- Safe `git pull --ff-only`
- Commit and push changes to `main`
- CSRF protection for every write operation
- Designed to sit behind cPanel Directory Privacy

## Deploy on the Namecheap server

From the repository clone:

```bash
cd ~/repos/the-one-apps
git fetch origin feature/dev-hub-ui
git checkout feature/dev-hub-ui
chmod +x devhub/deploy.sh
./devhub/deploy.sh
```

The deployment script backs up an existing `index.html`, `index.php` and `api.php` before replacing public Dev Hub files. It does **not** alter `.htaccess`, `.well-known`, cPanel Directory Privacy, SSL or the Android projects.

After testing, switch the repository back to `main` if required.
