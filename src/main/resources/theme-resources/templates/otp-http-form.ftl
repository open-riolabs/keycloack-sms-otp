<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('otp'); section>
    <#if section = "header">
        ${msg("otpHttpTitle")}
    <#elseif section = "form">
        <form id="kc-otp-http-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="otp" class="${properties.kcLabelClass!}">${msg("otpHttpLabel")}</label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <input id="otp" name="otp" type="text"
                           inputmode="numeric" autocomplete="one-time-code"
                           pattern="[0-9]*" autofocus
                           class="${properties.kcInputClass!}"
                           aria-invalid="<#if messagesPerField.existsError('otp')>true</#if>"/>
                    <#if messagesPerField.existsError('otp')>
                        <span id="input-error-otp" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('otp'))?no_esc}
                        </span>
                    </#if>
                </div>
            </div>

            <div class="${properties.kcFormGroupClass!} ${properties.kcFormSettingClass!}">
                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                           name="submit" id="kc-submit" type="submit" value="${msg("otpHttpSubmit")}"/>
                </div>
            </div>

            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-resend" class="${properties.kcFormOptionsWrapperClass!}">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                            name="resend" id="kc-resend" type="submit" value="true">
                        ${msg("otpHttpResend")}
                    </button>
                </div>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
