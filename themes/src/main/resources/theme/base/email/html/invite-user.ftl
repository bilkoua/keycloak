<#import "template.ftl" as layout>
<@layout.emailLayout>
${kcSanitize(msg("inviteUserBodyHtml",link, linkExpiration, realmName, linkExpirationFormatter(linkExpiration)))?no_esc}
</@layout.emailLayout>
