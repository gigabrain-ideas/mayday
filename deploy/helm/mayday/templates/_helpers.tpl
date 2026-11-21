{{- define "mayday.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "mayday.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "mayday.selector" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "mayday.secretName" -}}
{{- default (include "mayday.fullname" .) .Values.existingSecret -}}
{{- end -}}
{{- define "mayday.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "mayday.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
{{/* Credential and identity settings of the agent. The token proxy gets them instead of Mayday when it is enabled. */}}
{{- define "mayday.llmIdentityEnv" -}}
{{- $l := .Values.llm -}}
{{- $wi := .Values.workloadIdentity.enabled -}}
{{- $credential := $l.credential | default (ternary "oauth2" "" $wi) -}}
{{- $assertion := $l.assertionFile | default (ternary (include "mayday.workloadTokenPath" .) "" $wi) -}}
{{- $vars := dict "MAYDAY_LLM_AUTH_STYLE" $l.authStyle "MAYDAY_LLM_CREDENTIAL" $credential "MAYDAY_LLM_ISSUER" $l.issuer "MAYDAY_LLM_TOKEN_URL" $l.tokenUrl "MAYDAY_LLM_GRANT" $l.grant "MAYDAY_LLM_CLIENT_AUTH" $l.clientAuth "MAYDAY_LLM_CLIENT_ID" $l.clientId "MAYDAY_LLM_SCOPE" $l.scope "MAYDAY_LLM_ASSERTION_FILE" $assertion "MAYDAY_LLM_TOKEN_FILE" $l.tokenFile -}}
{{- range $k := keys $vars | sortAlpha }}
{{- with get $vars $k }}
- { name: {{ $k }}, value: {{ . | quote }} }
{{- end }}
{{- end }}
{{- with $l.headers }}
{{- $pairs := list }}
{{- range $k, $v := . }}{{ $pairs = append $pairs (printf "%s=%s" $k $v) }}{{ end }}
- { name: MAYDAY_LLM_HEADERS, value: {{ join "," $pairs | quote }} }
{{- end }}
{{- with $l.tokenParams }}
{{- $pairs := list }}
{{- range $k, $v := . }}{{ $pairs = append $pairs (printf "%s=%s" $k $v) }}{{ end }}
- { name: MAYDAY_LLM_TOKEN_PARAMS, value: {{ join "," $pairs | quote }} }
{{- end }}
{{- with $l.apiKeySecret.name }}
- name: MAYDAY_LLM_API_KEY
  valueFrom: { secretKeyRef: { name: {{ . }}, key: {{ $l.apiKeySecret.key }} } }
{{- end }}
{{- with $l.clientSecretSecret.name }}
- name: MAYDAY_LLM_CLIENT_SECRET
  valueFrom: { secretKeyRef: { name: {{ . }}, key: {{ $l.clientSecretSecret.key }} } }
{{- end }}
{{- end -}}

{{- define "mayday.workloadTokenPath" -}}/var/run/secrets/mayday/workload-identity/token{{- end -}}
