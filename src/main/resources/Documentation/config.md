Configuration
=============

The configuration of the @PLUGIN@ plugin is done in the `gerrit.config`
file. `auth.type` must be set to `OAUTH`:

```
[auth]
  type = OAUTH
```

Google and GitHub specific providers are configured under @PLUGIN@ section,
appended with provider suffix: `-google-oauth` and `-github-oauth`:

```
  [plugin "@PLUGIN@-google-oauth"]
    client-id = "<client-id>"
    client-secret = "<client-secret>"
    link-to-existing-openid-accounts = true

  [plugin "@PLUGIN@-github-oauth"]
    client-id = "<client-id>"
    client-secret = "<client-secret>"
```

When one from the sections above is omitted, OAuth SSO is used.
The login form with provider selection isn’t shown. When both
sections are omitted, Gerrit will not start.

Google OAuth provider seamlessly supports linking of OAuth identity
to existing OpenID accounts. This feature is deactivated by default.
To activate it, add

```
plugin.gerrit-oauth-provider-google-oauth.link-to-existing-openid-accounts = true
```

to Google OAuth configuration section.

## Obtaining provider authorizations

### Google

To obtain client-id and client-secret for Google OAuth, go to
[Google Developers Console](https://console.developers.google.com):

- Create a project

  ![Create a porject](images/google-1.png)

- Go inside the created project

- In "APIs & auth"/"Credentials" select "Create new Client ID" and
create Client ID for a Web application

  ![Create Client ID for a Web application](images/google-2.png)

- Enter additional information about the project, which will be
  presented to user during the authentication process

  ![Enter additional information](images/google-3.png)

- Specify authorized redirect URL: `<canonical-web-uri-of-gerrit>/oauth`

  ![Specify authorized redirect URI](images/google-4.png)

After the final step, the page will show generated client id and
secret.

![Generated id and secret](images/google-5.png)

### GitHub

To obtain client-id and client-secret for GitHub OAuth, go to
[Applications settings in your GitHub account](https://github.com/settings/applications):

- Select "Register new application" and enter information about the
  application.

  Note that it is important that authorization callback URL points to
  `<canonical-web-uri-of-gerrit>/oauth`.

  ![Register new application on GitHub](images/github-1.png)
  

After application is registered, the page will show generated client id and
secret.

![Generated client id and secret](images/github-2.png)
