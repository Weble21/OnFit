# Run after starting compose.auth.yaml. Read credentials from environment or ignored root .env.
param(
    [string]$KeycloakUrl = 'http://localhost:8081',
    [string]$Realm = 'onfit',
    [string]$CredentialsFile
)

$ErrorActionPreference = 'Stop'
$base = $KeycloakUrl.TrimEnd('/')
$localCredentials = @{}
if (-not $CredentialsFile) { $CredentialsFile = Join-Path $PSScriptRoot '../../.env' }
if (Test-Path -LiteralPath $CredentialsFile) {
    foreach ($line in [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $CredentialsFile).Path)) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)=(.*)$') {
            $localCredentials[$matches[1]] = $matches[2].Trim().Trim('"', "'")
        }
    }
}
function Get-Setting([string]$name) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ($value) { return $value }
    return $localCredentials[$name]
}
function Get-SettingOr([string]$name, [string]$default) {
    $value = Get-Setting $name
    if ($value) { return $value }
    return $default
}
$adminUser = Get-Setting 'ONFIT_KC_ADMIN_USER'
if (-not $adminUser) { $adminUser = 'admin' }
$adminPassword = Get-Setting 'ONFIT_KC_ADMIN_PASSWORD'
if (-not $adminPassword) { throw 'ONFIT_KC_ADMIN_PASSWORD is required (environment or root .env).' }

$providers = @(
    @{ Alias = 'google'; Name = 'Google'; Type = 'google'; ClientId = (Get-Setting 'ONFIT_GOOGLE_CLIENT_ID'); Secret = (Get-Setting 'ONFIT_GOOGLE_CLIENT_SECRET'); Config = @{
        # Signature validation needs Google's signing keys; the built-in provider does not fill this URL.
        jwksUrl = 'https://www.googleapis.com/oauth2/v3/certs'
        issuer = 'https://accounts.google.com'
    } },
    @{ Alias = 'naver'; Name = 'Naver'; Type = 'oidc'; ClientId = (Get-Setting 'ONFIT_NAVER_CLIENT_ID'); Secret = (Get-Setting 'ONFIT_NAVER_CLIENT_SECRET'); Config = @{
        authorizationUrl = 'https://nid.naver.com/oauth2/authorize'
        tokenUrl = 'https://nid.naver.com/oauth2/token'
        jwksUrl = 'https://nid.naver.com/oauth2/jwks'
        issuer = 'https://nid.naver.com'
        # Naver's ID token carries no nickname, picture or email even with the "profile" scope (checked
        # 2026-10-07), so only "openid" is requested. Override with ONFIT_NAVER_SCOPE if Naver changes this.
        defaultScope = Get-SettingOr 'ONFIT_NAVER_SCOPE' 'openid'
        # Naver's userinfo has no top-level "sub" (claims are wrapped in "response"), which Keycloak rejects
        # as a subject mismatch. Profile data would need a custom Naver provider extension.
        disableUserInfo = 'true'
    } },
    @{ Alias = 'kakao'; Name = 'Kakao'; Type = 'oidc'; ClientId = (Get-Setting 'ONFIT_KAKAO_CLIENT_ID'); Secret = (Get-Setting 'ONFIT_KAKAO_CLIENT_SECRET'); Config = @{
        authorizationUrl = 'https://kauth.kakao.com/oauth/authorize'
        tokenUrl = 'https://kauth.kakao.com/oauth/token'
        userInfoUrl = 'https://kapi.kakao.com/v1/oidc/userinfo'
        jwksUrl = 'https://kauth.kakao.com/.well-known/jwks.json'
        issuer = 'https://kauth.kakao.com'
        # Kakao asks only for the consent items named in scope, so nickname and picture must be listed.
        # Each item must also be enabled under the app's consent items, or Kakao answers KOE205.
        defaultScope = Get-SettingOr 'ONFIT_KAKAO_SCOPE' 'openid profile_nickname profile_image'
    } }
)

foreach ($provider in $providers) {
    if ([bool]$provider.ClientId -xor [bool]$provider.Secret) {
        throw "Both ONFIT_$($provider.Alias.ToUpper())_CLIENT_ID and ONFIT_$($provider.Alias.ToUpper())_CLIENT_SECRET are required."
    }
}
$selected = @($providers | Where-Object { $_.ClientId -and $_.Secret })
if ($selected.Count -eq 0) { throw 'Set at least one provider client ID and secret before configuring social login.' }

$token = Invoke-RestMethod -Method Post -Uri "$base/realms/master/protocol/openid-connect/token" -Body @{
    grant_type = 'password'; client_id = 'admin-cli'; username = $adminUser; password = $adminPassword
}
$headers = @{ Authorization = "Bearer $($token.access_token)" }
$profileEndpoint = "$base/admin/realms/$([uri]::EscapeDataString($Realm))/users/profile"
$userProfile = Invoke-RestMethod -Method Get -Uri $profileEndpoint -Headers $headers
$optionalAttributes = @($userProfile.attributes | Where-Object { $_.name -in @('email', 'firstName', 'lastName') })
if ($optionalAttributes.Count -ne 3) { throw 'Expected email, firstName, and lastName in the Keycloak user profile.' }
$profileChanged = $false
foreach ($attribute in $optionalAttributes) {
    if ($null -ne $attribute.required) {
        $attribute.required = $null
        $profileChanged = $true
    }
}
# Keycloak drops attributes the user profile does not declare, so the provider nickname and picture
# need declared attributes. Only admins (and the broker mappers) write them; users can see their own.
foreach ($attributeName in @('nickname', 'picture')) {
    if (-not ($userProfile.attributes | Where-Object { $_.name -eq $attributeName })) {
        $userProfile.attributes += [pscustomobject]@{
            name = $attributeName
            displayName = $attributeName
            validations = @{ length = @{ max = $(if ($attributeName -eq 'picture') { 2048 } else { 255 }) } }
            permissions = @{ view = @('admin', 'user'); edit = @('admin') }
            multivalued = $false
        }
        $profileChanged = $true
    }
}
if ($profileChanged) {
    Invoke-RestMethod -Method Put -Uri $profileEndpoint -Headers $headers -ContentType 'application/json' `
        -Body ($userProfile | ConvertTo-Json -Depth 20 -Compress) | Out-Null
}
$endpoint = "$base/admin/realms/$([uri]::EscapeDataString($Realm))/identity-provider/instances"
$authEndpoint = "$base/admin/realms/$([uri]::EscapeDataString($Realm))/authentication/flows"
$socialFlow = 'onfit social first broker login'
$flows = @(Invoke-RestMethod -Method Get -Uri $authEndpoint -Headers $headers)
if (-not ($flows | Where-Object { $_.alias -eq $socialFlow })) {
    $copyUri = "$authEndpoint/$([uri]::EscapeDataString('first broker login'))/copy"
    Invoke-RestMethod -Method Post -Uri $copyUri -Headers $headers -ContentType 'application/json' `
        -Body (@{ newName = $socialFlow } | ConvertTo-Json -Compress) | Out-Null
}
$executionUri = "$authEndpoint/$([uri]::EscapeDataString($socialFlow))/executions"
$executions = Invoke-RestMethod -Method Get -Uri $executionUri -Headers $headers
$review = @($executions | Where-Object { $_.providerId -eq 'idp-review-profile' })
if ($review.Count -ne 1) { throw "Expected one Review Profile execution in '$socialFlow'." }
if ($review[0].requirement -ne 'DISABLED') {
    Invoke-RestMethod -Method Put -Uri $executionUri -Headers $headers -ContentType 'application/json' `
        -Body (@{ id = $review[0].id; requirement = 'DISABLED' } | ConvertTo-Json -Compress) | Out-Null
}

foreach ($provider in $selected) {
    $config = @{
        clientId = $provider.ClientId
        clientSecret = $provider.Secret
        clientAuthMethod = 'client_secret_post'
        validateSignature = 'true'
        useJwksUrl = 'true'
        syncMode = 'IMPORT'
    }
    foreach ($entry in $provider.Config.GetEnumerator()) { $config[$entry.Key] = $entry.Value }
    $body = @{
        alias = $provider.Alias
        displayName = $provider.Name
        providerId = $provider.Type
        enabled = $true
        trustEmail = $false
        storeToken = $false
        firstBrokerLoginFlowAlias = $socialFlow
        config = $config
    } | ConvertTo-Json -Depth 5 -Compress
    $instance = "$endpoint/$($provider.Alias)"
    try {
        Invoke-RestMethod -Method Get -Uri $instance -Headers $headers | Out-Null
        Invoke-RestMethod -Method Put -Uri $instance -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
    } catch {
        if ($_.Exception.Response.StatusCode -ne [System.Net.HttpStatusCode]::NotFound) { throw }
        Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
    }
    # Keep the provider's nickname and picture separate from Keycloak's unique, immutable account ID.
    # The realm's "profile" scope already puts the nickname and picture attributes into the app's ID token.
    # Google does not expose a nickname; use its display name as the closest available value.
    $google = $provider.Type -eq 'google'
    $mapperType = if ($google) { 'google-user-attribute-mapper' } else { 'oidc-user-attribute-idp-mapper' }
    $attributeClaims = [ordered]@{ nickname = $(if ($google) { 'name' } else { 'nickname' }); picture = 'picture' }
    $mapperEndpoint = "$instance/mappers"
    # Windows PowerShell returns a JSON array as one object; the pipeline unrolls it into the mappers.
    $mappers = @(Invoke-RestMethod -Method Get -Uri $mapperEndpoint -Headers $headers | ForEach-Object { $_ })
    foreach ($attribute in $attributeClaims.GetEnumerator()) {
        $mapperName = "onfit $($attribute.Key)"
        # FORCE refreshes the value on every login, so a changed nickname or photo follows the provider.
        $mapperConfig = if ($google) {
            @{ jsonField = $attribute.Value; userAttribute = $attribute.Key; syncMode = 'FORCE' }
        } else {
            @{ claim = $attribute.Value; 'user.attribute' = $attribute.Key; syncMode = 'FORCE' }
        }
        $mapperBody = @{
            name = $mapperName
            identityProviderAlias = $provider.Alias
            identityProviderMapper = $mapperType
            config = $mapperConfig
        }
        $mapper = $mappers | Where-Object { $_.name -eq $mapperName } | Select-Object -First 1
        if ($mapper) {
            $mapperBody.id = $mapper.id
            Invoke-RestMethod -Method Put -Uri "$mapperEndpoint/$($mapper.id)" -Headers $headers `
                -ContentType 'application/json' -Body ($mapperBody | ConvertTo-Json -Depth 5 -Compress) | Out-Null
        } else {
            Invoke-RestMethod -Method Post -Uri $mapperEndpoint -Headers $headers `
                -ContentType 'application/json' -Body ($mapperBody | ConvertTo-Json -Depth 5 -Compress) | Out-Null
        }
    }
    Write-Host "$($provider.Name) configured. Callback: $base/realms/$Realm/broker/$($provider.Alias)/endpoint"
}

$envFile = Join-Path $PSScriptRoot '../../frontend/.env.local'
$existing = if (Test-Path $envFile) { @(Get-Content $envFile | Where-Object { $_ -notmatch '^VITE_SOCIAL_PROVIDERS=' }) } else { @() }
$enabled = @($providers | ForEach-Object {
    try {
        $instance = "$endpoint/$($_.Alias)"
        $current = Invoke-RestMethod -Method Get -Uri $instance -Headers $headers
        if ($current.enabled) { $_.Alias }
    } catch {
        if ($_.Exception.Response.StatusCode -ne [System.Net.HttpStatusCode]::NotFound) { throw }
    }
})
$lines = @($existing) + "VITE_SOCIAL_PROVIDERS=$($enabled -join ',')"
[System.IO.File]::WriteAllText($envFile, ($lines -join [Environment]::NewLine) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))
Write-Host 'Updated frontend/.env.local. Restart Vite or rebuild the production frontend.'
